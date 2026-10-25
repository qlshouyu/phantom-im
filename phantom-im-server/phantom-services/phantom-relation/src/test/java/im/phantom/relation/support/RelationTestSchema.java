package im.phantom.relation.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** 关系链测试使用的 H2(MySQL 模式) 表结构。 */
public final class RelationTestSchema {

    private RelationTestSchema() {}

    public static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE app_user(user_id VARCHAR(64) PRIMARY KEY, app_id VARCHAR(64) NOT NULL,
                  external_user_id VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL,
                  minor_mode BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL, deleted_at BIGINT, UNIQUE(app_id, external_user_id))
                """);
        jdbc.execute("""
                CREATE TABLE user_profile(user_id VARCHAR(64) PRIMARY KEY, nickname VARCHAR(64) NOT NULL,
                  avatar_url VARCHAR(1024) NOT NULL DEFAULT '', ext_json VARCHAR(4096) NOT NULL DEFAULT '{}',
                  display_snapshot VARCHAR(64), updated_at BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE friend_relation(user_id VARCHAR(64) NOT NULL, friend_id VARCHAR(64) NOT NULL,
                  created_at BIGINT NOT NULL, PRIMARY KEY(user_id, friend_id),
                  CONSTRAINT chk_friend_relation_self CHECK(user_id <> friend_id))
                """);
        jdbc.execute("""
                CREATE TABLE friend_request(request_id VARCHAR(64) PRIMARY KEY, app_id VARCHAR(64) NOT NULL,
                  from_user_id VARCHAR(64) NOT NULL, to_user_id VARCHAR(64) NOT NULL,
                  verification_message VARCHAR(60) NOT NULL DEFAULT '', status VARCHAR(24) NOT NULL,
                  created_at BIGINT NOT NULL, handled_at BIGINT, read_at BIGINT, pending_key VARCHAR(160),
                  UNIQUE(pending_key), CONSTRAINT chk_friend_request_self CHECK(from_user_id <> to_user_id))
                """);
        jdbc.execute("""
                CREATE TABLE friend_group(group_id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL,
                  name VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, UNIQUE(user_id, name))
                """);
        jdbc.execute("""
                CREATE TABLE friend_group_member(group_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL,
                  friend_id VARCHAR(64) NOT NULL, PRIMARY KEY(group_id, friend_id))
                """);
        jdbc.execute("""
                CREATE TABLE friend_setting(user_id VARCHAR(64) NOT NULL, friend_id VARCHAR(64) NOT NULL,
                  remark VARCHAR(64) NOT NULL DEFAULT '', pinned BOOLEAN NOT NULL DEFAULT FALSE,
                  updated_at BIGINT NOT NULL, PRIMARY KEY(user_id, friend_id))
                """);
        jdbc.execute("""
                CREATE TABLE user_relation_setting(user_id VARCHAR(64) PRIMARY KEY,
                  auto_accept_requests BOOLEAN NOT NULL DEFAULT FALSE,
                  friend_limit INT NOT NULL DEFAULT 10000, updated_at BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE blacklist(user_id VARCHAR(64) NOT NULL, blocked_id VARCHAR(64) NOT NULL,
                  created_at BIGINT NOT NULL, PRIMARY KEY(user_id, blocked_id),
                  CONSTRAINT chk_blacklist_self CHECK(user_id <> blocked_id))
                """);
        jdbc.execute("""
                CREATE TABLE qr_card(card_id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL,
                  token_hash VARCHAR(128) NOT NULL, token_value VARCHAR(128) NOT NULL,
                  status VARCHAR(24) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT,
                  UNIQUE(token_hash))
                """);
        jdbc.execute("""
                CREATE TABLE sync_outbox(outbox_id VARCHAR(64) PRIMARY KEY,
                  aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(128) NOT NULL,
                  event_type INT NOT NULL, user_id VARCHAR(64) NOT NULL, payload BLOB NOT NULL,
                  status VARCHAR(24) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL, next_retry_at BIGINT NOT NULL, published_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE user_timeline(user_id VARCHAR(64) NOT NULL, user_seq BIGINT NOT NULL,
                  event_type INT NOT NULL, msg_id VARCHAR(64) NOT NULL, event_bytes BLOB NOT NULL,
                  event_time BIGINT NOT NULL, PRIMARY KEY(user_id, user_seq), UNIQUE(user_id, msg_id))
                """);
        jdbc.execute("CREATE TABLE seq_watermark(scope VARCHAR(160) PRIMARY KEY, seq BIGINT NOT NULL)");
    }

    public static void seedUser(JdbcTemplate jdbc, String appId, String userId, String nickname) {
        long now = 1L;
        jdbc.update("""
                INSERT INTO app_user(user_id, app_id, external_user_id, status, minor_mode, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', FALSE, ?, ?)
                """, userId, appId, "ext-" + userId, now, now);
        jdbc.update("""
                INSERT INTO user_profile(user_id, nickname, avatar_url, ext_json, updated_at)
                VALUES (?, ?, '', '{}', ?)
                """, userId, nickname, now);
    }
}
