package im.phantom.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** JdbcTemplate 用户仓库。 */
@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findByExternalUserId(String appId, String externalUserId) {
        return jdbc.query("SELECT user_id FROM app_user WHERE app_id=? AND external_user_id=?",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), appId, externalUserId);
    }

    @Override
    public boolean exists(String appId, String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE app_id=? AND user_id=?",
                Integer.class, appId, userId);
        return count != null && count > 0;
    }

    @Override
    public Optional<UserRecord> findById(String appId, String userId) {
        return jdbc.query("""
                SELECT u.user_id, u.app_id, u.external_user_id, u.status,
                       p.nickname, p.avatar_url, p.ext_json, p.display_snapshot
                FROM app_user u JOIN user_profile p ON p.user_id=u.user_id
                WHERE u.app_id=? AND u.user_id=?
                """, rs -> rs.next() ? Optional.of(new UserRecord(
                rs.getString("user_id"), rs.getString("app_id"), rs.getString("external_user_id"),
                rs.getString("status"), rs.getString("nickname"), rs.getString("avatar_url"),
                rs.getString("ext_json"), rs.getString("display_snapshot"))) : Optional.empty(), appId, userId);
    }

    @Override
    public void insert(String appId, String userId, String externalUserId, String nickname,
                       String avatarUrl, String extJson, long now) {
        jdbc.update("""
                INSERT INTO app_user(user_id, app_id, external_user_id, status, minor_mode, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """, userId, appId, externalUserId, now, now);
        jdbc.update("""
                INSERT INTO user_profile(user_id, nickname, avatar_url, ext_json, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, userId, nickname, avatarUrl, extJson, now);
    }

    @Override
    public void updateProfile(String userId, String nickname, String avatarUrl, String extJson, long now) {
        jdbc.update("UPDATE user_profile SET nickname=?, avatar_url=?, ext_json=?, updated_at=? WHERE user_id=?",
                nickname, avatarUrl, extJson, now, userId);
    }

    @Override
    public void updateStatus(String userId, String status, long now) {
        jdbc.update("UPDATE app_user SET status=?, updated_at=? WHERE user_id=?", status, now, userId);
    }

    @Override
    public void markDeleted(String userId, String releasedExternalUserId, long now) {
        jdbc.update("UPDATE app_user SET status='DELETED', external_user_id=?, deleted_at=?, updated_at=? WHERE user_id=?",
                releasedExternalUserId, now, now, userId);
    }

    @Override
    public void anonymizeProfile(String userId, long now) {
        jdbc.update("""
                UPDATE user_profile SET display_snapshot=nickname, nickname='已注销用户', avatar_url='', updated_at=?
                WHERE user_id=?
                """, now, userId);
    }

    @Override
    public void leaveGroupsAndConversations(String userId, long now) {
        jdbc.update("UPDATE group_member SET status='LEFT', left_at=? WHERE user_id=? AND status='ACTIVE'",
                now, userId);
        jdbc.update("UPDATE conversation_member SET left_at=? WHERE user_id=? AND left_at IS NULL", now, userId);
    }
}
