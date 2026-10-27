package im.phantom.api.route;

/**
 * 用户最新 user_seq 查询契约：网关认证握手时填充 AuthResp.max_user_seq，
 * 供客户端判断需要 Sync 多少增量。由 sync module 本地实现。
 */
public interface UserSeqQuery {
    long maxUserSeq(String userId);
}
