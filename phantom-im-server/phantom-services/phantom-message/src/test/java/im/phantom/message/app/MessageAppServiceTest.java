package im.phantom.message.app;

import im.phantom.api.relation.RelationService;
import im.phantom.common.BizException;
import im.phantom.common.ConvIds;
import im.phantom.common.ErrorCodes;
import im.phantom.common.risk.ContentModerationProvider;
import im.phantom.common.risk.ModerationDecision;
import im.phantom.common.risk.ModerationRequest;
import im.phantom.infra.mq.TxMqProducer;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.message.infra.ConvTimelineDao;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.SendMsgReq;
import im.phantom.proto.v1.SendMsgResp;
import im.phantom.proto.v1.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageAppServiceTest {

    private static final String SENDER = "u_alice";
    private static final String CONV = ConvIds.single(SENDER, "u_bob");

    @Mock
    private ConvTimelineDao dao;
    @Mock
    private SeqAllocator seqAllocator;
    @Mock
    private TxMqProducer producer;
    @Mock
    private ObjectProvider<RelationService> relationServices;
    @Mock
    private RelationService relationService;
    @Mock
    private ObjectProvider<ContentModerationProvider> moderationProviders;

    private MessageAppService service;

    @BeforeEach
    void setUp() {
        service = new MessageAppService(dao, seqAllocator, producer);
    }

    @Test
    void rejectsTextLongerThanFiveThousandBeforeAllocatingSequence() throws Exception {
        SendMsgReq req = request("c-long", "x".repeat(5001));

        assertThatThrownBy(() -> service.handleSend(SENDER, req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCodes.MSG_TOO_LONG);

        verify(seqAllocator, never()).nextConvSeq(anyString());
        verify(producer, never()).sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void duplicateClientMessageReturnsOriginalResultWithoutWritingAgain() throws Exception {
        when(dao.findByClientMsgId(SENDER, "c-1"))
                .thenReturn(new ConvTimelineDao.Existing("m_original", 42L, 123456L));

        SendMsgResp resp = service.handleSend(SENDER, request("c-1", "hello"));

        assertThat(resp.getMsgId()).isEqualTo("m_original");
        assertThat(resp.getConvSeq()).isEqualTo(42L);
        assertThat(resp.getServerTime()).isEqualTo(123456L);
        verify(seqAllocator, never()).nextConvSeq(anyString());
        verify(producer, never()).sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void successfulSendAllocatesSequenceAndCommitsTransaction() throws Exception {
        when(dao.findByClientMsgId(SENDER, "c-2")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(9L);
        when(producer.sendInTransaction(eq("TOPIC_MSG_EVENT"), eq("new_msg"), eq(CONV), anyString(), any(), any()))
                .thenReturn(true);

        SendMsgResp resp = service.handleSend(SENDER, request("c-2", "hello"));

        assertThat(resp.getConvSeq()).isEqualTo(9L);
        assertThat(resp.getMsgId()).startsWith("m_");
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(producer).sendInTransaction(eq("TOPIC_MSG_EVENT"), eq("new_msg"), eq(CONV), eq(resp.getMsgId()),
                body.capture(), any());
        assertThat(body.getValue()).isNotEmpty();
    }

    @Test
    void failedLocalTransactionIsNotReportedAsSuccess() throws Exception {
        when(dao.findByClientMsgId(SENDER, "c-3")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(10L);
        when(producer.sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(false);
        when(dao.findByClientMsgId(SENDER, "c-3")).thenReturn(null);

        assertThatThrownBy(() -> service.handleSend(SENDER, request("c-3", "hello")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCodes.SERVER_ERROR);
    }

    @Test
    void blacklistPolicyRejectsMessageBeforeAllocatingSequence() throws Exception {
        when(relationServices.getIfAvailable()).thenReturn(relationService);
        doThrow(new BizException(ErrorCodes.REJECTED_BY_PEER, "对方拒绝接收消息"))
                .when(relationService).authorizeDirectMessage("app1", SENDER, "u_bob");
        MessageAppService guarded = new MessageAppService(dao, seqAllocator, producer, relationServices,
                null, null, null, null, null);

        assertThatThrownBy(() -> guarded.handleSend("app1", SENDER, request("c-blocked", "hello")))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(ErrorCodes.REJECTED_BY_PEER);
        verify(seqAllocator, never()).nextConvSeq(anyString());
        verify(producer, never()).sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void expiredBurnAfterReadHistoryReturnsTombstoneWithoutContent() {
        Msg expired = Msg.newBuilder().setMsgId("m_burn").setClientMsgId("c_burn")
                .setConvId(CONV).setConvType(ConvType.CONV_TYPE_SINGLE).setSenderId(SENDER)
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContent(MsgContent.newBuilder().setText(TextContent.newBuilder().setText("secret")))
                .setConvSeq(1).setServerTime(System.currentTimeMillis() - 10_000)
                .setBurnAfterReadTtlSeconds(1).build();
        when(dao.historyForUser(SENDER, CONV, Long.MAX_VALUE, 51)).thenReturn(List.of(expired));

        var page = service.history(SENDER, CONV, 0, 50);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.contentJson()).isEqualTo("{}");
            assertThat(item.burnAfterReadTtlSeconds()).isEqualTo(1);
        });
    }

    @Test
    void asyncReviewBlocksSuspiciousMediaAndTriggersSystemRecall() throws Exception {
        CountDownLatch reviewed = new CountDownLatch(1);
        ContentModerationProvider provider = new ContentModerationProvider() {
            @Override public ModerationDecision moderate(ModerationRequest request) {
                return ModerationDecision.review("image.review");
            }
            @Override public Optional<ModerationDecision> review(ModerationRequest request) {
                reviewed.countDown();
                return Optional.of(ModerationDecision.block("image.rejected"));
            }
        };
        when(moderationProviders.getIfAvailable()).thenReturn(provider);
        MessageAppService guarded = new MessageAppService(dao, seqAllocator, producer,
                null, null, null, null, moderationProviders, null);
        when(dao.findByClientMsgId(SENDER, "c-review")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(11L);
        when(producer.sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);
        when(dao.findByMsgId(anyString())).thenAnswer(invocation -> Msg.newBuilder()
                .setMsgId(invocation.getArgument(0)).setConvId(CONV)
                .setSenderId(SENDER).setServerTime(1000).build());
        when(dao.markRecalled(any(), anyLong())).thenReturn(true);

        guarded.handleSend("app", SENDER, request("c-review", "image"));

        assertThat(reviewed.await(3, TimeUnit.SECONDS)).isTrue();
        verify(dao, org.mockito.Mockito.timeout(3000)).markRecalled(any(), anyLong());
    }

    private SendMsgReq request(String clientMsgId, String text) {
        return SendMsgReq.newBuilder()
                .setClientMsgId(clientMsgId)
                .setConvId(CONV)
                .setConvType(ConvType.CONV_TYPE_SINGLE)
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContent(MsgContent.newBuilder().setText(TextContent.newBuilder().setText(text)))
                .build();
    }
}
