mod support;
use phantom_sdk::*;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};
use tokio::sync::Notify;
struct Api {
    base: Arc<support::Server>,
    conv: Mutex<Conversation>,
    block: AtomicBool,
    reject: AtomicBool,
    entered: Notify,
    release: Notify,
}
fn conversation() -> Conversation {
    Conversation {
        conv_id: "gr_group".into(),
        conv_type: ConversationType::Group,
        target_id: "group".into(),
        title: "群聊".into(),
        avatar_url: None,
        last_message_digest: "".into(),
        last_message_at: 0,
        unread_count: 10,
        mention_me: true,
        pinned: false,
        pinned_at: 0,
        muted: false,
        draft: "".into(),
        draft_edited_at: 0,
        max_conv_seq: 10,
        read_conv_seq: 0,
        read_only: false,
    }
}
impl Api {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            base: Arc::default(),
            conv: Mutex::new(conversation()),
            block: AtomicBool::new(false),
            reject: AtomicBool::new(false),
            entered: Notify::new(),
            release: Notify::new(),
        })
    }
    fn client(self: &Arc<Self>) -> PhantomClient {
        let mut host = support::host(&self.base, Arc::new(MemoryStoreFactory::new()));
        host.api = self.clone();
        PhantomClient::with_host(ClientOptions::new("https://example.test", "app"), host).unwrap()
    }
}
impl PlatformApi for Api {
    fn execute(
        &self,
        request: ApiRequest,
        identity: HttpIdentity,
        id: String,
    ) -> SdkFuture<'_, ApiResponse> {
        Box::pin(async move {
            Ok(match request {
                ApiRequest::ListConversations { .. } => {
                    ApiResponse::ListConversations(Box::new(ConversationPage {
                        items: vec![self.conv.lock().unwrap().clone()],
                        next_cursor: None,
                        has_more: false,
                        max_user_seq: 0,
                        badge_count: 10,
                    }))
                }
                ApiRequest::GetConversation { .. } => {
                    ApiResponse::GetConversation(Box::new(self.conv.lock().unwrap().clone()))
                }
                ApiRequest::UpdateConversation { patch, .. } => {
                    self.entered.notify_one();
                    if self.block.load(Ordering::Acquire) {
                        self.release.notified().await;
                    }
                    if self.reject.load(Ordering::Acquire) {
                        let mut e = SdkError::new(ErrorKind::Business, "服务端拒绝修改");
                        e.server_code = Some(5002);
                        e.outcome = OperationOutcome::Rejected;
                        return Err(e);
                    }
                    let mut conv = self.conv.lock().unwrap();
                    if let Some(pinned) = patch.pinned {
                        conv.pinned = pinned;
                        conv.pinned_at = 10;
                    }
                    if let Some(muted) = patch.muted {
                        conv.muted = muted;
                    }
                    ApiResponse::UpdateConversation(Box::new(conv.clone()))
                }
                ApiRequest::MarkRead {
                    conv_id,
                    read_conv_seq,
                } => ApiResponse::MarkRead(Box::new(ReadPosition {
                    conv_id,
                    user_id: identity.user_id,
                    read_conv_seq,
                })),
                ApiRequest::GetReadPosition { conv_id } => {
                    ApiResponse::GetReadPosition(Box::new(ReadPosition {
                        conv_id,
                        user_id: identity.user_id,
                        read_conv_seq: 1,
                    }))
                }
                ApiRequest::SaveDraft { input } => {
                    ApiResponse::SaveDraft(Box::new(ConversationDraft {
                        conv_id: input.conv_id,
                        draft: input.draft,
                        edited_at: input.edited_at.unwrap(),
                    }))
                }
                ApiRequest::ClearDraft { conv_id, edited_at } => {
                    ApiResponse::ClearDraft(Box::new(ConversationDraft {
                        conv_id,
                        draft: "".into(),
                        edited_at: edited_at.unwrap(),
                    }))
                }
                ApiRequest::HandleMessageRequest { accept, .. } => {
                    let mut conv = self.conv.lock().unwrap().clone();
                    conv.read_only = !accept;
                    ApiResponse::HandleMessageRequest(Box::new(conv))
                }
                request => return self.base.execute(request, identity, id).await,
            })
        })
    }
}
fn call() -> CallOptions {
    CallOptions {
        timeout_ms: Some(3_000),
        cancellation: None,
    }
}
async fn seq(sdk: &PhantomClient, value: Seq) {
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while sdk.get_sync_status().unwrap().applied_user_seq < value {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
}
#[tokio::test]
async fn pending_http_is_concurrent_and_rollback_preserves_newer_sync_fields_even_when_values_match()
 {
    let api = Api::new();
    let sdk = api.client();
    support::ready(&sdk).await;
    api.block.store(true, Ordering::Release);
    api.reject.store(true, Ordering::Release);
    let task = {
        let sdk = sdk.clone();
        tokio::spawn(async move {
            sdk.update_conversation(
                "gr_group".into(),
                ConversationPatch {
                    pinned: Some(true),
                    muted: None,
                },
                call(),
            )
            .await
        })
    };
    api.entered.notified().await;
    assert!(
        sdk.get_conversation_state("gr_group".into())
            .unwrap()
            .unwrap()
            .pinned
    );
    tokio::time::timeout(std::time::Duration::from_secs(1), sdk.list_friends(call()))
        .await
        .unwrap()
        .unwrap();
    api.base.push(
        0,
        GatewayPush::Sync {
            event: Box::new(SyncEvent {
                user_seq: 1,
                event_time: 20,
                payload: SyncPayload::ConversationState(ConversationStateEvent {
                    conv_id: "gr_group".into(),
                    field: ConversationStateField::Muted,
                    bool_value: true,
                    op_time: 20,
                }),
            }),
            ack_required: false,
        },
    );
    seq(&sdk, 1).await;
    api.release.notify_one();
    let error = task.await.unwrap().unwrap_err();
    assert_eq!(error.server_code, Some(5002));
    let current = sdk
        .get_conversation_state("gr_group".into())
        .unwrap()
        .unwrap();
    assert!(!current.pinned);
    assert!(current.muted);
    let task = {
        let sdk = sdk.clone();
        tokio::spawn(async move {
            sdk.update_conversation(
                "gr_group".into(),
                ConversationPatch {
                    pinned: Some(true),
                    muted: None,
                },
                call(),
            )
            .await
        })
    };
    api.entered.notified().await;
    api.base.push(
        0,
        GatewayPush::Sync {
            event: Box::new(SyncEvent {
                user_seq: 2,
                event_time: i64::MAX,
                payload: SyncPayload::ConversationState(ConversationStateEvent {
                    conv_id: "gr_group".into(),
                    field: ConversationStateField::Pinned,
                    bool_value: true,
                    op_time: i64::MAX,
                }),
            }),
            ack_required: false,
        },
    );
    seq(&sdk, 2).await;
    api.release.notify_one();
    task.await.unwrap().unwrap_err();
    let current = sdk
        .get_conversation_state("gr_group".into())
        .unwrap()
        .unwrap();
    assert!(current.pinned);
    assert_eq!(current.pinned_at, i64::MAX);
    sdk.shutdown(call()).await.unwrap();
}
#[tokio::test]
async fn read_positions_only_advance_drafts_keep_newest_edit_and_request_result_is_typed() {
    let api = Api::new();
    let sdk = api.client();
    support::ready(&sdk).await;
    sdk.mark_read("gr_group".into(), 10, call()).await.unwrap();
    let read = sdk
        .get_read_position("gr_group".into(), call())
        .await
        .unwrap();
    assert_eq!(read.read_conv_seq, 10);
    assert_eq!(
        sdk.get_conversation_state("gr_group".into())
            .unwrap()
            .unwrap()
            .unread_count,
        0
    );
    sdk.save_draft(
        DraftInput {
            conv_id: "gr_group".into(),
            draft: "较新".into(),
            edited_at: Some(20),
        },
        call(),
    )
    .await
    .unwrap();
    let draft = sdk
        .save_draft(
            DraftInput {
                conv_id: "gr_group".into(),
                draft: "过期".into(),
                edited_at: Some(10),
            },
            call(),
        )
        .await
        .unwrap();
    assert_eq!(draft.draft, "较新");
    assert_eq!(
        sdk.clear_draft("gr_group".into(), Some(15), call())
            .await
            .unwrap()
            .draft,
        "较新"
    );
    assert!(
        sdk.clear_draft("gr_group".into(), Some(30), call())
            .await
            .unwrap()
            .draft
            .is_empty()
    );
    assert!(
        !sdk.handle_message_request("gr_group".into(), true, call())
            .await
            .unwrap()
            .read_only
    );
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn consecutive_optimistic_failures_restore_committed_baseline_in_both_completion_orders() {
    for newer_first in [false, true] {
        let api = Api::new();
        let sdk = api.client();
        support::ready(&sdk).await;
        api.block.store(true, Ordering::Release);
        api.reject.store(true, Ordering::Release);
        let first = {
            let sdk = sdk.clone();
            tokio::spawn(async move {
                sdk.update_conversation(
                    "gr_group".into(),
                    ConversationPatch {
                        pinned: Some(true),
                        muted: None,
                    },
                    call(),
                )
                .await
            })
        };
        api.entered.notified().await;
        let token = CancellationToken::new();
        let second = {
            let sdk = sdk.clone();
            let cancellation = token.clone();
            tokio::spawn(async move {
                sdk.update_conversation(
                    "gr_group".into(),
                    ConversationPatch {
                        pinned: Some(true),
                        muted: None,
                    },
                    CallOptions {
                        timeout_ms: Some(3000),
                        cancellation: Some(cancellation),
                    },
                )
                .await
            })
        };
        api.entered.notified().await;
        if newer_first {
            token.cancel();
            second.await.unwrap().unwrap_err();
            api.release.notify_one();
            first.await.unwrap().unwrap_err();
        } else {
            api.release.notify_one();
            first.await.unwrap().unwrap_err();
            api.release.notify_one();
            second.await.unwrap().unwrap_err();
        }
        assert!(
            !sdk.get_conversation_state("gr_group".into())
                .unwrap()
                .unwrap()
                .pinned
        );
        sdk.shutdown(call()).await.unwrap();
    }
}
