package im.phantom.conversation.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** 会话模块测试表结构（H2 MySQL 模式）。 */
public final class ConversationTestSchema {

    private ConversationTestSchema() {}

    public static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE app_user(user_id VARCHAR(64) PRIMARY KEY, app_id VARCHAR(64) NOT NULL,
                  external_user_id VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL,
                  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE user_profile(user_id VARCHAR(64) PRIMARY KEY, nickname VARCHAR(64) NOT NULL,
                  avatar_url VARCHAR(1024) NOT NULL DEFAULT '', ext_json VARCHAR(4096) NOT NULL DEFAULT '{}',
                  updated_at BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE conversation(conv_id VARCHAR(128) PRIMARY KEY, conv_type INT NOT NULL,
                  owner_app_id VARCHAR(64) NOT NULL, target_id VARCHAR(64), status VARCHAR(24) NOT NULL,
                  read_only BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE conversation_member(conv_id VARCHAR(128) NOT NULL, user_id VARCHAR(64) NOT NULL,
                  member_role VARCHAR(24) NOT NULL, joined_at BIGINT NOT NULL, left_at BIGINT,
                  PRIMARY KEY(conv_id, user_id))
                """);
        jdbc.execute("""
                CREATE TABLE user_conversation(user_id VARCHAR(64) NOT NULL, conv_id VARCHAR(128) NOT NULL,
                  pinned BOOLEAN NOT NULL DEFAULT FALSE, pinned_at BIGINT NOT NULL DEFAULT 0,
                  muted BOOLEAN NOT NULL DEFAULT FALSE, deleted BOOLEAN NOT NULL DEFAULT FALSE,
                  draft VARCHAR(5000), draft_edited_at BIGINT NOT NULL DEFAULT 0,
                  unread_count INT NOT NULL DEFAULT 0, mention_me BOOLEAN NOT NULL DEFAULT FALSE,
                  is_request BOOLEAN NOT NULL DEFAULT FALSE, last_message_at BIGINT NOT NULL DEFAULT 0,
                  last_message_digest VARCHAR(512) NOT NULL DEFAULT '',
                  last_message_conv_seq BIGINT NOT NULL DEFAULT 0,
                  read_only BOOLEAN NOT NULL DEFAULT FALSE, updated_at BIGINT NOT NULL,
                  PRIMARY KEY(user_id, conv_id))
                """);
        jdbc.execute("""
                CREATE TABLE read_position(user_id VARCHAR(64) NOT NULL, conv_id VARCHAR(128) NOT NULL,
                  read_conv_seq BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL,
                  PRIMARY KEY(user_id, conv_id))
                """);
        jdbc.execute("""
                CREATE TABLE chat_group(group_id VARCHAR(64) PRIMARY KEY, conv_id VARCHAR(128) NOT NULL UNIQUE,
                  name VARCHAR(128) NOT NULL, avatar_url VARCHAR(1024) NOT NULL DEFAULT '',
                  owner_id VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, invite_verification BOOLEAN NOT NULL DEFAULT TRUE,
                  member_count INT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE group_member(group_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL,
                  member_role VARCHAR(24) NOT NULL, group_nickname VARCHAR(64) NOT NULL DEFAULT '',
                  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', joined_at BIGINT NOT NULL, left_at BIGINT,
                  PRIMARY KEY(group_id, user_id))
                """);
        jdbc.execute("""
                CREATE TABLE conv_timeline(conv_id VARCHAR(128) NOT NULL, conv_seq BIGINT NOT NULL,
                  msg_id VARCHAR(64) NOT NULL, client_msg_id VARCHAR(64) NOT NULL,
                  sender_id VARCHAR(64) NOT NULL, conv_type INT NOT NULL, msg_type INT NOT NULL,
                  msg_bytes BLOB NOT NULL, server_time BIGINT NOT NULL, recalled BOOLEAN NOT NULL DEFAULT FALSE,
                  PRIMARY KEY(conv_id, conv_seq), UNIQUE(msg_id))
                """);
        jdbc.execute("CREATE TABLE seq_watermark(scope VARCHAR(160) PRIMARY KEY, seq BIGINT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE user_timeline(user_id VARCHAR(64) NOT NULL, user_seq BIGINT NOT NULL,
                  event_type INT NOT NULL, msg_id VARCHAR(64), event_bytes BLOB NOT NULL,
                  event_time BIGINT NOT NULL, PRIMARY KEY(user_id,user_seq), UNIQUE(user_id,msg_id))
                """);
        jdbc.execute("""
                CREATE TABLE message_meta(msg_id VARCHAR(64) PRIMARY KEY, status INT NOT NULL,
                  restricted BOOLEAN NOT NULL DEFAULT FALSE, reply_to_msg_id VARCHAR(64),
                  mentions_json VARCHAR(4096) NOT NULL, burn_ttl_seconds INT NOT NULL DEFAULT 0,
                  recalled_at BIGINT, delivered_at BIGINT, read_at BIGINT, updated_at BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE sync_outbox(outbox_id VARCHAR(64) PRIMARY KEY,
                  aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(128) NOT NULL,
                  event_type INT NOT NULL, user_id VARCHAR(64) NOT NULL, payload BLOB NOT NULL,
                  status VARCHAR(24) NOT NULL, attempts INT NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL, next_retry_at BIGINT NOT NULL, published_at BIGINT)
                """);
    }

    public static void seedUser(JdbcTemplate jdbc, String userId, String nickname) {
        jdbc.update("""
                INSERT INTO app_user(user_id, app_id, external_user_id, status, created_at, updated_at)
                VALUES (?, 'app1', ?, 'ACTIVE', 1, 1)
                """, userId, "ext-" + userId);
        jdbc.update("""
                INSERT INTO user_profile(user_id, nickname, avatar_url, ext_json, updated_at)
                VALUES (?, ?, '', '{}', 1)
                """, userId, nickname);
    }
}
