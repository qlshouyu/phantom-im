package im.phantom.infra.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 设备级 ACK 游标（message-fanout-delivery spec）：记录某用户某设备已确认到的 user_seq。
 * 与 SyncReq 游标同一进度（M0 客户端自带游标，服务端仅记录用于将来的 5 秒重推 M1）。
 */
@Component
public class AckCursorStore {

    private final StringRedisTemplate redis;

    public AckCursorStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String userId, String deviceId) {
        return "ack:" + userId + ":" + deviceId;
    }

    /** 只前进：新值不大于旧值则忽略。 */
    public void advance(String userId, String deviceId, long userSeq) {
        String k = key(userId, deviceId);
        String cur = redis.opsForValue().get(k);
        if (cur == null || Long.parseLong(cur) < userSeq) {
            redis.opsForValue().set(k, String.valueOf(userSeq));
        }
    }

    public long get(String userId, String deviceId) {
        String v = redis.opsForValue().get(key(userId, deviceId));
        return v == null ? 0L : Long.parseLong(v);
    }
}
