use phantom_domain::*;
use phantom_protocol::Frame;
use phantom_transport::*;
use std::time::Duration;

fn frame(cid: u64, command: i32) -> Frame {
    Frame {
        cid,
        command,
        code: 0,
        payload: vec![],
        raw_bytes: vec![],
    }
}

#[test]
fn service_prefix_and_dynamic_segments_remain_separate() {
    let base = BaseUrl::parse("https://EXAMPLE.test:443/phantom/").unwrap();
    assert_eq!(base.normalized(), "https://example.test/phantom");
    assert_eq!(
        base.websocket().unwrap().as_str(),
        "wss://example.test/phantom/ws"
    );
    let url = base
        .endpoint(
            &["users".into(), "a/b ?#%中文".into()],
            &[("q".into(), "a&b".into())],
        )
        .unwrap();
    assert_eq!(
        url.as_str(),
        "https://example.test/phantom/users/a%2Fb%20%3F%23%25%E4%B8%AD%E6%96%87?q=a%26b"
    );
    assert!(base.endpoint(&["..".into()], &[]).is_err());
    assert_eq!(
        BaseUrl::parse("http://example.test")
            .unwrap()
            .websocket()
            .unwrap()
            .as_str(),
        "ws://example.test/ws"
    );
}
#[test]
fn invalid_base_urls_are_rejected_without_network() {
    for input in [
        "",
        "ws://example.test",
        "ftp://example.test",
        "https://example.test/?",
        "https://example.test/#",
        "https://user:secret@example.test",
        " https://example.test",
        "/relative",
    ] {
        assert_eq!(
            BaseUrl::parse(input).unwrap_err().kind,
            ErrorKind::InvalidArgument,
            "输入 {input}"
        );
    }
}
#[tokio::test]
async fn simultaneous_responses_pair_by_cid_and_mismatched_commands_fail() {
    let pending = PendingRequests::new();
    let first = pending.register(2).unwrap();
    let second = pending.register(0x101).unwrap();
    let first_cid = first.cid;
    let second_cid = second.cid;
    pending.complete(frame(second_cid, 0x101));
    pending.complete(frame(first_cid, 2));
    assert_eq!(second.wait().await.unwrap().cid, second_cid);
    assert_eq!(first.wait().await.unwrap().cid, first_cid);
    let wrong = pending.register(2).unwrap();
    pending.complete(frame(wrong.cid, 4));
    assert_eq!(wrong.wait().await.unwrap_err().kind, ErrorKind::Protocol);
    assert!(pending.is_empty());
}
#[tokio::test]
async fn deadline_drop_and_close_remove_pending_requests_and_ignore_late_frames() {
    let pending = PendingRequests::new();
    let ticket = pending.register(2).unwrap();
    let cid = ticket.cid;
    assert!(
        tokio::time::timeout(Duration::from_millis(1), ticket.wait())
            .await
            .is_err()
    );
    assert!(pending.is_empty());
    assert!(matches!(
        pending.complete(frame(cid, 2)),
        ResponseDisposition::Late
    ));
    let ticket = pending.register(2).unwrap();
    pending.close(SdkError::new(ErrorKind::Network, "测试断线"));
    assert_eq!(ticket.wait().await.unwrap_err().kind, ErrorKind::Network);
    assert!(pending.is_empty());
    assert!(pending.register(2).is_err());
    assert!(matches!(
        pending.complete(frame(0, 0x102)),
        ResponseDisposition::Push
    ));
}
