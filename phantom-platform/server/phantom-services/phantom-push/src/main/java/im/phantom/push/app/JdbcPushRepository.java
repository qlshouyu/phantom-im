package im.phantom.push.app;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPushRepository {

    private final JdbcTemplate jdbc;

    public JdbcPushRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void upsertToken(String id, String userId, String token, String tokenHash,
                            int deviceType, String provider, long now) {
        jdbc.update("""
                INSERT INTO push_token(push_token_id, user_id, token, token_hash, device_type, provider,
                    status, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), token=VALUES(token),
                    device_type=VALUES(device_type), provider=VALUES(provider), status='ACTIVE',
                    updated_at=VALUES(updated_at)
                """, id, userId, token, tokenHash, deviceType, provider, now);
    }

    public void deleteToken(String userId, String tokenHash, long now) {
        jdbc.update("UPDATE push_token SET status='DELETED', updated_at=? " +
                "WHERE user_id=? AND token_hash=?", now, userId, tokenHash);
    }

    public List<TokenRecord> activeTokens(String userId) {
        return jdbc.query("SELECT push_token_id, token, provider FROM push_token " +
                        "WHERE user_id=? AND status='ACTIVE'", (rs, row) -> new TokenRecord(
                rs.getString(1), rs.getString(2), rs.getString(3)), userId);
    }

    public void invalidateToken(String tokenId, long now) {
        jdbc.update("UPDATE push_token SET status='INVALID', updated_at=? WHERE push_token_id=?",
                now, tokenId);
    }

    public Settings settings(String userId) {
        return jdbc.query("SELECT push_enabled, preview_enabled, privacy_policy_url, terms_url " +
                        "FROM push_setting WHERE user_id=?",
                rs -> rs.next() ? new Settings(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3),
                        rs.getString(4)) : new Settings(true, true, "", ""), userId);
    }

    public void saveSettings(String userId, boolean pushEnabled, boolean previewEnabled, long now) {
        jdbc.update("""
                INSERT INTO push_setting(user_id, push_enabled, preview_enabled, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE push_enabled=VALUES(push_enabled),
                    preview_enabled=VALUES(preview_enabled), updated_at=VALUES(updated_at)
                """, userId, pushEnabled, previewEnabled, now);
    }

    public void savePrivacyLinks(String userId, String privacyPolicyUrl, String termsUrl, long now) {
        jdbc.update("""
                INSERT INTO push_setting(user_id, privacy_policy_url, terms_url, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE privacy_policy_url=VALUES(privacy_policy_url),
                    terms_url=VALUES(terms_url), updated_at=VALUES(updated_at)
                """, userId, privacyPolicyUrl, termsUrl, now);
    }

    public boolean muted(String userId, String convId) {
        Boolean muted = jdbc.query("SELECT muted FROM user_conversation WHERE user_id=? AND conv_id=?",
                rs -> rs.next() ? rs.getBoolean(1) : null, userId, convId);
        return Boolean.TRUE.equals(muted);
    }

    public int badgeCount(String userId) {
        Integer count = jdbc.query("SELECT COALESCE(SUM(unread_count),0) FROM user_conversation " +
                        "WHERE user_id=? AND deleted=FALSE AND muted=FALSE AND is_request=FALSE",
                rs -> rs.next() ? rs.getInt(1) : 0, userId);
        return count == null ? 0 : count;
    }

    public String displayName(String userId) {
        String value = jdbc.query("SELECT nickname FROM user_profile WHERE user_id=?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        return value == null ? userId : value;
    }

    public String groupName(String convId) {
        String value = jdbc.query("SELECT name FROM chat_group WHERE conv_id=?",
                rs -> rs.next() ? rs.getString(1) : null, convId);
        return value == null ? "群聊" : value;
    }

    public boolean claimDelivery(String deliveryId, String userId, String msgId, String convId,
                                 String tokenId, long now) {
        try {
            jdbc.update("INSERT INTO push_delivery(delivery_id, user_id, msg_id, conv_id, token_id, " +
                            "status, attempts, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)",
                    deliveryId, userId, msgId, convId, tokenId, now, now);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return false;
        }
    }

    public void delivered(String deliveryId, String providerMessageId, long now) {
        jdbc.update("UPDATE push_delivery SET status='DELIVERED', attempts=attempts+1, " +
                        "provider_msg_id=?, updated_at=? WHERE delivery_id=?",
                providerMessageId, now, deliveryId);
    }

    public void failed(String deliveryId, long now) {
        jdbc.update("UPDATE push_delivery SET status='FAILED', attempts=attempts+1, updated_at=? " +
                "WHERE delivery_id=?", now, deliveryId);
    }

    public void retryScheduled(String deliveryId, long now) {
        jdbc.update("UPDATE push_delivery SET status='PENDING', attempts=attempts+1, updated_at=? " +
                "WHERE delivery_id=?", now, deliveryId);
    }

    public void dead(String deliveryId, long now) {
        jdbc.update("UPDATE push_delivery SET status='DEAD', attempts=attempts+1, updated_at=? " +
                "WHERE delivery_id=?", now, deliveryId);
    }

    public record TokenRecord(String tokenId, String token, String provider) {}
    public record Settings(boolean pushEnabled, boolean previewEnabled,
                           String privacyPolicyUrl, String termsUrl) {}
}
