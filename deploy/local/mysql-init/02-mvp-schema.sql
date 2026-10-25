-- Phantom IM MVP 建表 DDL（幂等、只增不删）
-- 对应变更 implement-mvp-p0-p1。M0 表结构保持兼容，新增领域独立建表。
CREATE DATABASE IF NOT EXISTS phantom_im DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE phantom_im;

-- ---------------------------------------------------------------------------
-- 应用、密钥、用户
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS app (
  app_id       VARCHAR(64)  NOT NULL,
  name         VARCHAR(128) NOT NULL,
  status       VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
  created_at   BIGINT       NOT NULL,
  updated_at   BIGINT       NOT NULL,
  PRIMARY KEY (app_id),
  KEY idx_app_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用（租户）';

CREATE TABLE IF NOT EXISTS app_secret_version (
  app_id        VARCHAR(64) NOT NULL,
  secret_version VARCHAR(32) NOT NULL,
  secret_hash   VARCHAR(128) NOT NULL COMMENT 'AppSecret 单向摘要，不保存明文',
  secret_ciphertext VARBINARY(1024) NOT NULL COMMENT '加密后的 AppSecret，仅供验签使用',
  status        VARCHAR(24) NOT NULL COMMENT 'ACTIVE / GRACE / REVOKED',
  created_at    BIGINT      NOT NULL,
  expires_at    BIGINT      NULL,
  PRIMARY KEY (app_id, secret_version),
  KEY idx_secret_active (app_id, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AppSecret 版本';

CREATE TABLE IF NOT EXISTS app_config (
  app_id                       VARCHAR(64) NOT NULL,
  relation_hosted              TINYINT(1)  NOT NULL DEFAULT 1,
  read_receipt_enabled         TINYINT(1)  NOT NULL DEFAULT 1,
  burn_after_read_enabled      TINYINT(1)  NOT NULL DEFAULT 0,
  burn_after_read_ttl_seconds  INT         NOT NULL DEFAULT 0,
  sdk_profile_write_enabled    TINYINT(1)  NOT NULL DEFAULT 1,
  max_devices                  INT         NOT NULL DEFAULT 3,
  updated_at                   BIGINT      NOT NULL,
  PRIMARY KEY (app_id),
  CONSTRAINT fk_app_config_app FOREIGN KEY (app_id) REFERENCES app(app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用级功能配置';

CREATE TABLE IF NOT EXISTS app_user (
  user_id           VARCHAR(64)  NOT NULL COMMENT 'Phantom ID，永不复用',
  app_id            VARCHAR(64)  NOT NULL,
  external_user_id  VARCHAR(64)  NOT NULL,
  status            VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
  minor_mode        TINYINT(1)   NOT NULL DEFAULT 0,
  created_at        BIGINT       NOT NULL,
  updated_at        BIGINT       NOT NULL,
  deleted_at        BIGINT       NULL,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_app_external (app_id, external_user_id),
  KEY idx_user_status (app_id, status),
  CONSTRAINT fk_app_user_app FOREIGN KEY (app_id) REFERENCES app(app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Phantom ID 用户与 external_user_id 映射';

CREATE TABLE IF NOT EXISTS user_profile (
  user_id        VARCHAR(64)  NOT NULL,
  nickname       VARCHAR(64)  NOT NULL,
  avatar_url     VARCHAR(1024) NOT NULL DEFAULT '',
  ext_json       JSON         NOT NULL,
  display_snapshot VARCHAR(64) NULL COMMENT '删除后历史展示快照',
  updated_at     BIGINT       NOT NULL,
  PRIMARY KEY (user_id),
  KEY idx_profile_nickname (nickname),
  CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES app_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户极简资料';

-- ---------------------------------------------------------------------------
-- 关系链
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS friend_relation (
  user_id    VARCHAR(64) NOT NULL,
  friend_id  VARCHAR(64) NOT NULL,
  created_at BIGINT      NOT NULL,
  PRIMARY KEY (user_id, friend_id),
  KEY idx_friend_relation_friend (friend_id),
  CONSTRAINT chk_friend_relation_self CHECK (user_id <> friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单向好友关系';

CREATE TABLE IF NOT EXISTS friend_request (
  request_id           VARCHAR(64)  NOT NULL,
  app_id               VARCHAR(64)  NOT NULL,
  from_user_id         VARCHAR(64)  NOT NULL,
  to_user_id           VARCHAR(64)  NOT NULL,
  verification_message VARCHAR(60)  NOT NULL DEFAULT '',
  status               VARCHAR(24)  NOT NULL COMMENT 'PENDING / ACCEPTED / REJECTED / IGNORED',
  created_at           BIGINT       NOT NULL,
  handled_at           BIGINT       NULL,
  read_at              BIGINT       NULL,
  pending_key          VARCHAR(160) NULL COMMENT '待处理申请幂等键；处理后置空',
  PRIMARY KEY (request_id),
  KEY idx_request_to_status (to_user_id, status, created_at),
  KEY idx_request_from (from_user_id, created_at),
  UNIQUE KEY uk_friend_request_pending (pending_key),
  CONSTRAINT chk_friend_request_self CHECK (from_user_id <> to_user_id),
  CONSTRAINT chk_friend_request_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED','IGNORED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友申请';

CREATE TABLE IF NOT EXISTS friend_group (
  group_id   VARCHAR(64)  NOT NULL,
  user_id    VARCHAR(64)  NOT NULL,
  name       VARCHAR(64)  NOT NULL,
  created_at BIGINT       NOT NULL,
  PRIMARY KEY (group_id),
  KEY idx_friend_group_user (user_id, created_at),
  UNIQUE KEY uk_friend_group_name (user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友自定义分组';

CREATE TABLE IF NOT EXISTS friend_group_member (
  group_id  VARCHAR(64) NOT NULL,
  user_id   VARCHAR(64) NOT NULL,
  friend_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (group_id, friend_id),
  KEY idx_group_member_user (user_id, friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友分组成员';

CREATE TABLE IF NOT EXISTS friend_setting (
  user_id                VARCHAR(64) NOT NULL,
  friend_id              VARCHAR(64) NOT NULL,
  remark                 VARCHAR(64) NOT NULL DEFAULT '',
  pinned                 TINYINT(1)  NOT NULL DEFAULT 0,
  updated_at             BIGINT      NOT NULL,
  PRIMARY KEY (user_id, friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友备注与置顶设置';

CREATE TABLE IF NOT EXISTS user_relation_setting (
  user_id               VARCHAR(64) NOT NULL,
  auto_accept_requests  TINYINT(1)  NOT NULL DEFAULT 0,
  friend_limit          INT         NOT NULL DEFAULT 10000,
  updated_at            BIGINT      NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户关系设置';

CREATE TABLE IF NOT EXISTS blacklist (
  user_id    VARCHAR(64) NOT NULL,
  blocked_id VARCHAR(64) NOT NULL,
  created_at BIGINT      NOT NULL,
  PRIMARY KEY (user_id, blocked_id),
  KEY idx_blacklist_blocked (blocked_id),
  CONSTRAINT chk_blacklist_self CHECK (user_id <> blocked_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='黑名单';

CREATE TABLE IF NOT EXISTS qr_card (
  card_id    VARCHAR(64) NOT NULL,
  user_id    VARCHAR(64) NOT NULL,
  token_hash VARCHAR(128) NOT NULL,
  token_value VARCHAR(128) NOT NULL COMMENT '仅用于向名片所有者返回二维码内容',
  status     VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at BIGINT      NOT NULL,
  expires_at BIGINT      NULL,
  PRIMARY KEY (card_id),
  UNIQUE KEY uk_qr_token (token_hash),
  KEY idx_qr_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人二维码名片';

-- 兼容已创建的 M0/早期 MVP 关系链表：按需补列、唯一键和约束。
DROP PROCEDURE IF EXISTS phantom_add_column_if_missing;
DROP PROCEDURE IF EXISTS phantom_add_index_if_missing;
DROP PROCEDURE IF EXISTS phantom_add_constraint_if_missing;
DELIMITER //
CREATE PROCEDURE phantom_add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64),
                                               IN p_ddl VARCHAR(512))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name=p_table AND column_name=p_column) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
CREATE PROCEDURE phantom_add_index_if_missing(IN p_table VARCHAR(64), IN p_index VARCHAR(64),
                                              IN p_ddl VARCHAR(512))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name=p_table AND index_name=p_index) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
CREATE PROCEDURE phantom_add_constraint_if_missing(IN p_table VARCHAR(64), IN p_constraint VARCHAR(64),
                                                   IN p_ddl VARCHAR(512))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                 WHERE constraint_schema=DATABASE() AND table_name=p_table
                   AND constraint_name=p_constraint) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;
CALL phantom_add_column_if_missing('friend_request', 'read_at', 'read_at BIGINT NULL AFTER handled_at');
CALL phantom_add_column_if_missing('friend_request', 'pending_key',
  'pending_key VARCHAR(160) NULL COMMENT ''待处理申请幂等键；处理后置空'' AFTER read_at');
CALL phantom_add_index_if_missing('friend_request', 'uk_friend_request_pending',
  'UNIQUE KEY uk_friend_request_pending (pending_key)');
CALL phantom_add_constraint_if_missing('friend_relation', 'chk_friend_relation_self',
  'CONSTRAINT chk_friend_relation_self CHECK (user_id <> friend_id)');
CALL phantom_add_constraint_if_missing('friend_request', 'chk_friend_request_self',
  'CONSTRAINT chk_friend_request_self CHECK (from_user_id <> to_user_id)');
CALL phantom_add_constraint_if_missing('friend_request', 'chk_friend_request_status',
  'CONSTRAINT chk_friend_request_status CHECK (status IN (''PENDING'',''ACCEPTED'',''REJECTED'',''IGNORED''))');
CALL phantom_add_index_if_missing('friend_group', 'uk_friend_group_name',
  'UNIQUE KEY uk_friend_group_name (user_id, name)');
CALL phantom_add_constraint_if_missing('blacklist', 'chk_blacklist_self',
  'CONSTRAINT chk_blacklist_self CHECK (user_id <> blocked_id)');
CALL phantom_add_column_if_missing('qr_card', 'token_value',
  'token_value VARCHAR(128) NOT NULL DEFAULT '''' COMMENT ''仅用于向名片所有者返回二维码内容'' AFTER token_hash');
DROP PROCEDURE phantom_add_column_if_missing;
DROP PROCEDURE phantom_add_index_if_missing;
DROP PROCEDURE phantom_add_constraint_if_missing;

-- ---------------------------------------------------------------------------
-- 会话、群组与已读
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS conversation (
  conv_id      VARCHAR(128) NOT NULL,
  conv_type    INT          NOT NULL,
  owner_app_id VARCHAR(64)  NOT NULL,
  target_id    VARCHAR(64)  NULL COMMENT '单聊对端或群 ID',
  status       VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
  read_only    TINYINT(1)   NOT NULL DEFAULT 0,
  created_at   BIGINT       NOT NULL,
  updated_at   BIGINT       NOT NULL,
  PRIMARY KEY (conv_id),
  KEY idx_conv_app_type (owner_app_id, conv_type, updated_at),
  KEY idx_conv_target (target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话主表';

CREATE TABLE IF NOT EXISTS conversation_member (
  conv_id    VARCHAR(128) NOT NULL,
  user_id    VARCHAR(64)  NOT NULL,
  member_role VARCHAR(24) NOT NULL DEFAULT 'MEMBER',
  joined_at  BIGINT       NOT NULL,
  left_at    BIGINT       NULL,
  PRIMARY KEY (conv_id, user_id),
  KEY idx_conv_member_user (user_id, joined_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话成员';

CREATE TABLE IF NOT EXISTS user_conversation (
  user_id           VARCHAR(64)  NOT NULL,
  conv_id           VARCHAR(128) NOT NULL,
  pinned            TINYINT(1)   NOT NULL DEFAULT 0,
  pinned_at         BIGINT       NOT NULL DEFAULT 0,
  muted             TINYINT(1)   NOT NULL DEFAULT 0,
  deleted           TINYINT(1)   NOT NULL DEFAULT 0,
  draft             TEXT         NULL,
  draft_edited_at   BIGINT       NOT NULL DEFAULT 0,
  unread_count      INT          NOT NULL DEFAULT 0,
  mention_me        TINYINT(1)   NOT NULL DEFAULT 0,
  is_request        TINYINT(1)   NOT NULL DEFAULT 0,
  last_message_at   BIGINT       NOT NULL DEFAULT 0,
  last_message_digest VARCHAR(512) NOT NULL DEFAULT '',
  last_message_conv_seq BIGINT    NOT NULL DEFAULT 0,
  read_only         TINYINT(1)   NOT NULL DEFAULT 0,
  updated_at        BIGINT       NOT NULL,
  PRIMARY KEY (user_id, conv_id),
  KEY idx_user_conv_order (user_id, deleted, pinned, pinned_at, last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户会话状态与列表投影';

-- 兼容已创建的用户会话投影表。
DROP PROCEDURE IF EXISTS phantom_add_conversation_column_if_missing;
DELIMITER //
CREATE PROCEDURE phantom_add_conversation_column_if_missing(IN p_column VARCHAR(64), IN p_ddl VARCHAR(512))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='user_conversation'
                   AND column_name=p_column) THEN
    SET @ddl = CONCAT('ALTER TABLE user_conversation ADD COLUMN ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;
CALL phantom_add_conversation_column_if_missing('last_message_conv_seq',
  'last_message_conv_seq BIGINT NOT NULL DEFAULT 0 AFTER last_message_digest');
CALL phantom_add_conversation_column_if_missing('read_only',
  'read_only TINYINT(1) NOT NULL DEFAULT 0 AFTER last_message_conv_seq');
DROP PROCEDURE phantom_add_conversation_column_if_missing;

CREATE TABLE IF NOT EXISTS read_position (
  user_id        VARCHAR(64)  NOT NULL,
  conv_id        VARCHAR(128) NOT NULL,
  read_conv_seq  BIGINT       NOT NULL DEFAULT 0,
  updated_at     BIGINT       NOT NULL,
  PRIMARY KEY (user_id, conv_id),
  KEY idx_read_position_conv (conv_id, read_conv_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话已读位置，只允许前进';

CREATE TABLE IF NOT EXISTS chat_group (
  group_id    VARCHAR(64)  NOT NULL,
  conv_id     VARCHAR(128) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  avatar_url  VARCHAR(1024) NOT NULL DEFAULT '',
  owner_id    VARCHAR(64)  NOT NULL,
  status      VARCHAR(24)  NOT NULL COMMENT 'ACTIVE / DISSOLVED',
  invite_verification TINYINT(1) NOT NULL DEFAULT 1,
  member_invite_enabled TINYINT(1) NOT NULL DEFAULT 0,
  member_count INT         NOT NULL DEFAULT 0,
  created_at  BIGINT       NOT NULL,
  updated_at  BIGINT       NOT NULL,
  PRIMARY KEY (group_id),
  UNIQUE KEY uk_group_conv (conv_id),
  KEY idx_group_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群聊';

DROP PROCEDURE IF EXISTS phantom_add_group_column_if_missing;
DELIMITER //
CREATE PROCEDURE phantom_add_group_column_if_missing(IN p_column VARCHAR(64), IN p_ddl VARCHAR(512))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='chat_group' AND column_name=p_column) THEN
    SET @ddl = CONCAT('ALTER TABLE chat_group ADD COLUMN ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;
CALL phantom_add_group_column_if_missing('member_invite_enabled',
  'member_invite_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER invite_verification');
DROP PROCEDURE phantom_add_group_column_if_missing;

CREATE TABLE IF NOT EXISTS group_member (
  group_id       VARCHAR(64) NOT NULL,
  user_id        VARCHAR(64) NOT NULL,
  member_role    VARCHAR(24) NOT NULL COMMENT 'OWNER / ADMIN / MEMBER',
  group_nickname VARCHAR(64) NOT NULL DEFAULT '',
  status         VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  joined_at      BIGINT      NOT NULL,
  left_at        BIGINT      NULL,
  PRIMARY KEY (group_id, user_id),
  KEY idx_group_member_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群成员与角色';

CREATE TABLE IF NOT EXISTS group_mute (
  group_id   VARCHAR(64) NOT NULL,
  user_id    VARCHAR(64) NOT NULL COMMENT 'ALL 表示全员禁言',
  muted_until BIGINT     NULL COMMENT 'NULL 且 user_id 非空表示永久',
  enabled    TINYINT(1)  NOT NULL DEFAULT 1,
  operator_id VARCHAR(64) NOT NULL,
  updated_at BIGINT      NOT NULL,
  PRIMARY KEY (group_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成员禁言与全员禁言';

CREATE TABLE IF NOT EXISTS group_announcement (
  group_id    VARCHAR(64) NOT NULL,
  content     TEXT        NOT NULL,
  operator_id VARCHAR(64) NOT NULL,
  version     BIGINT      NOT NULL,
  updated_at  BIGINT      NOT NULL,
  PRIMARY KEY (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群公告';

CREATE TABLE IF NOT EXISTS group_announcement_read (
  group_id    VARCHAR(64) NOT NULL,
  user_id     VARCHAR(64) NOT NULL,
  version     BIGINT      NOT NULL,
  read_at     BIGINT      NOT NULL,
  PRIMARY KEY (group_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群公告阅读位置';

CREATE TABLE IF NOT EXISTS group_invite (
  invite_id  VARCHAR(64) NOT NULL,
  group_id   VARCHAR(64) NOT NULL,
  token_hash VARCHAR(128) NOT NULL,
  invite_type VARCHAR(24) NOT NULL COMMENT 'QR / LINK',
  status     VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_by VARCHAR(64) NOT NULL,
  created_at BIGINT      NOT NULL,
  expires_at BIGINT      NULL,
  PRIMARY KEY (invite_id),
  UNIQUE KEY uk_group_invite_token (token_hash),
  KEY idx_group_invite_group (group_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群二维码与邀请链接';

CREATE TABLE IF NOT EXISTS group_join_request (
  request_id  VARCHAR(64) NOT NULL,
  group_id    VARCHAR(64) NOT NULL,
  user_id     VARCHAR(64) NOT NULL,
  invite_id   VARCHAR(64) NULL,
  status      VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  created_at  BIGINT NOT NULL,
  handled_at  BIGINT NULL,
  operator_id VARCHAR(64) NULL,
  PRIMARY KEY (request_id),
  KEY idx_group_join_pending (group_id, status, created_at),
  KEY idx_group_join_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群邀请入群申请';

-- ---------------------------------------------------------------------------
-- 消息扩展、媒体与 outbox
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS message_meta (
  msg_id             VARCHAR(64) NOT NULL,
  status             INT         NOT NULL COMMENT 'MsgStatus 枚举值',
  restricted         TINYINT(1)  NOT NULL DEFAULT 0,
  reply_to_msg_id    VARCHAR(64) NULL,
  mentions_json      JSON        NOT NULL,
  burn_ttl_seconds   INT         NOT NULL DEFAULT 0,
  recalled_at        BIGINT      NULL,
  delivered_at       BIGINT      NULL,
  read_at            BIGINT      NULL,
  updated_at         BIGINT      NOT NULL,
  PRIMARY KEY (msg_id),
  KEY idx_message_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息状态与操作扩展';

CREATE TABLE IF NOT EXISTS message_media_ref (
  media_id   VARCHAR(64)  NOT NULL,
  msg_id     VARCHAR(64)  NOT NULL,
  conv_id    VARCHAR(128) NOT NULL,
  created_at BIGINT       NOT NULL,
  PRIMARY KEY (msg_id, media_id),
  KEY idx_message_media_access (media_id, conv_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息引用的媒体，用于访问控制和清理';

CREATE TABLE IF NOT EXISTS user_message_deletion (
  user_id    VARCHAR(64)  NOT NULL,
  msg_id     VARCHAR(64)  NOT NULL,
  conv_id    VARCHAR(128) NOT NULL,
  deleted_at BIGINT       NOT NULL,
  PRIMARY KEY (user_id, msg_id),
  KEY idx_user_message_deletion_conv (user_id, conv_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='仅当前用户可见的本地消息删除标记';

CREATE TABLE IF NOT EXISTS user_conversation_clear (
  user_id           VARCHAR(64)  NOT NULL,
  conv_id           VARCHAR(128) NOT NULL,
  cleared_conv_seq  BIGINT       NOT NULL,
  updated_at        BIGINT       NOT NULL,
  PRIMARY KEY (user_id, conv_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='仅当前用户的本地清空位置';

-- M0 只落 conv_timeline.msg_bytes。升级时把既有消息投影到 message_meta，
-- 状态按 SENT 回填（M0 只有成功落库的消息），mentions 按空数组回填。
-- 使用 NOT EXISTS 而不是 REPLACE，避免重复执行时覆盖运行期已推进的 DELIVERED/READ 状态。
INSERT INTO message_meta(msg_id, status, restricted, reply_to_msg_id, mentions_json,
                         burn_ttl_seconds, recalled_at, delivered_at, read_at, updated_at)
SELECT ct.msg_id, 2, FALSE, NULL, JSON_ARRAY(), 0, NULL, NULL, NULL,
       COALESCE(ct.server_time, UNIX_TIMESTAMP() * 1000)
FROM conv_timeline ct
WHERE NOT EXISTS (SELECT 1 FROM message_meta mm WHERE mm.msg_id = ct.msg_id);

CREATE TABLE IF NOT EXISTS media_object (
  media_id     VARCHAR(64)  NOT NULL,
  app_id       VARCHAR(64)  NOT NULL,
  owner_id     VARCHAR(64)  NOT NULL,
  storage_key  VARCHAR(512) NOT NULL,
  sha256       VARCHAR(64)  NOT NULL,
  mime_type    VARCHAR(128) NOT NULL,
  size_bytes   BIGINT       NOT NULL,
  media_type   VARCHAR(24)  NOT NULL COMMENT 'IMAGE / VOICE / VIDEO / FILE',
  thumb_key    VARCHAR(512) NULL,
  metadata_json JSON        NOT NULL,
  burn_after_read TINYINT(1) NOT NULL DEFAULT 0,
  status       VARCHAR(24)  NOT NULL DEFAULT 'READY',
  created_at   BIGINT       NOT NULL,
  expires_at   BIGINT       NULL,
  PRIMARY KEY (media_id),
  KEY idx_media_sha (app_id, sha256, status),
  KEY idx_media_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='媒体对象元数据';

CREATE TABLE IF NOT EXISTS media_upload (
  upload_id    VARCHAR(64)  NOT NULL,
  app_id       VARCHAR(64)  NOT NULL,
  owner_id     VARCHAR(64)  NOT NULL,
  file_name    VARCHAR(512) NOT NULL,
  sha256       VARCHAR(64)  NOT NULL,
  mime_type    VARCHAR(128) NOT NULL,
  size_bytes   BIGINT       NOT NULL,
  part_size    INT          NOT NULL,
  metadata_json JSON        NOT NULL,
  burn_after_read TINYINT(1) NOT NULL DEFAULT 0,
  burn_ttl_seconds INT      NOT NULL DEFAULT 0,
  status       VARCHAR(24)  NOT NULL COMMENT 'INIT / UPLOADING / COMPLETED / EXPIRED',
  media_id     VARCHAR(64)  NULL,
  created_at   BIGINT       NOT NULL,
  updated_at   BIGINT       NOT NULL,
  expires_at   BIGINT       NOT NULL,
  PRIMARY KEY (upload_id),
  KEY idx_upload_resume (app_id, owner_id, sha256, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='媒体上传会话';

CREATE TABLE IF NOT EXISTS media_upload_part (
  upload_id   VARCHAR(64) NOT NULL,
  part_number INT         NOT NULL,
  etag        VARCHAR(128) NOT NULL,
  size_bytes  BIGINT      NOT NULL,
  created_at  BIGINT      NOT NULL,
  PRIMARY KEY (upload_id, part_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='媒体分片';

DROP PROCEDURE IF EXISTS phantom_add_media_column_if_missing;
DELIMITER //
CREATE PROCEDURE phantom_add_media_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64),
                                                     IN p_ddl VARCHAR(512))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name=p_table AND column_name=p_column) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;
CALL phantom_add_media_column_if_missing('media_object', 'burn_after_read',
  'burn_after_read TINYINT(1) NOT NULL DEFAULT 0 AFTER metadata_json');
CALL phantom_add_media_column_if_missing('media_upload', 'metadata_json',
  'metadata_json JSON NOT NULL AFTER part_size');
CALL phantom_add_media_column_if_missing('media_upload', 'burn_after_read',
  'burn_after_read TINYINT(1) NOT NULL DEFAULT 0 AFTER metadata_json');
CALL phantom_add_media_column_if_missing('media_upload', 'burn_ttl_seconds',
  'burn_ttl_seconds INT NOT NULL DEFAULT 0 AFTER burn_after_read');
DROP PROCEDURE phantom_add_media_column_if_missing;

CREATE TABLE IF NOT EXISTS sync_outbox (
  outbox_id     VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id  VARCHAR(128) NOT NULL,
  event_type    INT         NOT NULL,
  user_id       VARCHAR(64) NOT NULL,
  payload       MEDIUMBLOB  NOT NULL,
  status        VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempts      INT         NOT NULL DEFAULT 0,
  created_at    BIGINT      NOT NULL,
  next_retry_at BIGINT      NOT NULL,
  published_at  BIGINT      NULL,
  PRIMARY KEY (outbox_id),
  KEY idx_outbox_dispatch (status, next_retry_at, created_at),
  KEY idx_outbox_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='领域事件 outbox';

CREATE TABLE IF NOT EXISTS consumed_event (
  consumer_group VARCHAR(128) NOT NULL,
  event_id       VARCHAR(128) NOT NULL,
  status         VARCHAR(24)  NOT NULL COMMENT 'PROCESSING / DONE',
  claimed_at     BIGINT       NOT NULL,
  consumed_at    BIGINT       NULL,
  PRIMARY KEY (consumer_group, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消费者幂等记录';

-- ---------------------------------------------------------------------------
-- 设备、推送与搜索
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS device_registry (
  user_id       VARCHAR(64) NOT NULL,
  device_id     VARCHAR(128) NOT NULL,
  device_type   INT         NOT NULL,
  platform      VARCHAR(32) NOT NULL,
  device_name   VARCHAR(128) NOT NULL DEFAULT '',
  login_at      BIGINT      NOT NULL,
  last_active_at BIGINT     NOT NULL,
  location      VARCHAR(128) NOT NULL DEFAULT '',
  foreground    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, device_id),
  KEY idx_device_user_type (user_id, device_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备注册与在线元数据';

CREATE TABLE IF NOT EXISTS push_token (
  push_token_id VARCHAR(64) NOT NULL,
  user_id       VARCHAR(64) NOT NULL,
  token         VARCHAR(1024) NOT NULL,
  token_hash    VARCHAR(64) NOT NULL COMMENT '推送 Token 的 SHA-256，用于唯一约束',
  device_type   INT         NOT NULL,
  provider      VARCHAR(32) NOT NULL,
  status        VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  updated_at    BIGINT      NOT NULL,
  PRIMARY KEY (push_token_id),
  UNIQUE KEY uk_push_token_hash (token_hash),
  KEY idx_push_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备推送 Token';

CREATE TABLE IF NOT EXISTS push_setting (
  user_id             VARCHAR(64) NOT NULL,
  push_enabled        TINYINT(1)  NOT NULL DEFAULT 1,
  preview_enabled     TINYINT(1)  NOT NULL DEFAULT 1,
  privacy_policy_url  VARCHAR(1024) NOT NULL DEFAULT '',
  terms_url           VARCHAR(1024) NOT NULL DEFAULT '',
  updated_at          BIGINT      NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送与隐私链接设置';

CREATE TABLE IF NOT EXISTS push_delivery (
  delivery_id  VARCHAR(64) NOT NULL,
  user_id      VARCHAR(64) NOT NULL,
  msg_id       VARCHAR(64) NOT NULL,
  conv_id      VARCHAR(128) NOT NULL,
  token_id     VARCHAR(64) NOT NULL,
  status       VARCHAR(24) NOT NULL,
  attempts     INT         NOT NULL DEFAULT 0,
  provider_msg_id VARCHAR(128) NULL,
  created_at   BIGINT      NOT NULL,
  updated_at   BIGINT      NOT NULL,
  PRIMARY KEY (delivery_id),
  UNIQUE KEY uk_push_delivery_msg_token (msg_id, token_id),
  KEY idx_push_delivery_retry (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='离线推送幂等与重试记录';

CREATE TABLE IF NOT EXISTS search_document (
  doc_id       VARCHAR(128) NOT NULL,
  app_id       VARCHAR(64)  NOT NULL,
  doc_type     VARCHAR(24)  NOT NULL COMMENT 'CONTACT / GROUP / MESSAGE',
  owner_id     VARCHAR(64)  NOT NULL,
  conv_id      VARCHAR(128) NULL,
  msg_id       VARCHAR(64)  NULL,
  title        VARCHAR(256) NOT NULL DEFAULT '',
  content      TEXT         NULL,
  metadata_json JSON        NOT NULL,
  updated_at   BIGINT       NOT NULL,
  PRIMARY KEY (doc_id),
  KEY idx_search_owner_type (app_id, owner_id, doc_type, updated_at),
  KEY idx_search_title (title(191)),
  KEY idx_search_conv (conv_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='联系人/群/消息搜索文档';

-- ---------------------------------------------------------------------------
-- 风控、举报、处置、申诉与审计
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS report (
  report_id      VARCHAR(64) NOT NULL,
  app_id         VARCHAR(64) NOT NULL,
  reporter_id    VARCHAR(64) NOT NULL,
  target_type    VARCHAR(24) NOT NULL COMMENT 'MESSAGE / USER / GROUP',
  target_id      VARCHAR(128) NOT NULL,
  reason         VARCHAR(32) NOT NULL,
  description    VARCHAR(1000) NOT NULL DEFAULT '',
  snapshot_json  JSON        NULL,
  status         VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  created_at     BIGINT      NOT NULL,
  updated_at     BIGINT      NOT NULL,
  PRIMARY KEY (report_id),
  KEY idx_report_status (app_id, status, created_at),
  KEY idx_report_reporter_target (reporter_id, target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='举报单';

CREATE TABLE IF NOT EXISTS sanction (
  sanction_id   VARCHAR(64) NOT NULL,
  app_id        VARCHAR(64) NOT NULL,
  user_id       VARCHAR(64) NOT NULL,
  sanction_type VARCHAR(24) NOT NULL COMMENT 'WARNING / MUTE / BAN',
  reason        VARCHAR(1000) NOT NULL,
  starts_at     BIGINT      NOT NULL,
  ends_at       BIGINT      NULL,
  status        VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  operator_id   VARCHAR(64) NOT NULL,
  created_at    BIGINT      NOT NULL,
  PRIMARY KEY (sanction_id),
  KEY idx_sanction_user_status (user_id, status, ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账号处置';

CREATE TABLE IF NOT EXISTS appeal (
  appeal_id    VARCHAR(64) NOT NULL,
  sanction_id  VARCHAR(64) NOT NULL,
  user_id      VARCHAR(64) NOT NULL,
  content      TEXT        NOT NULL,
  status       VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  result       VARCHAR(1000) NULL,
  created_at   BIGINT      NOT NULL,
  updated_at   BIGINT      NOT NULL,
  PRIMARY KEY (appeal_id),
  KEY idx_appeal_user_status (user_id, status, created_at),
  KEY idx_appeal_sanction (sanction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='处置申诉';

CREATE TABLE IF NOT EXISTS audit_log (
  audit_id     VARCHAR(64) NOT NULL,
  app_id       VARCHAR(64) NOT NULL,
  actor_id     VARCHAR(64) NOT NULL,
  action       VARCHAR(64) NOT NULL,
  target_type  VARCHAR(32) NOT NULL,
  target_id    VARCHAR(128) NOT NULL,
  result       VARCHAR(24) NOT NULL,
  rule_version VARCHAR(64) NULL,
  detail_json  JSON        NULL,
  created_at   BIGINT      NOT NULL,
  PRIMARY KEY (audit_id),
  KEY idx_audit_app_time (app_id, created_at),
  KEY idx_audit_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='合规审计日志';

CREATE TABLE IF NOT EXISTS account_export (
  export_id   VARCHAR(64) NOT NULL,
  app_id      VARCHAR(64) NOT NULL,
  user_id     VARCHAR(64) NOT NULL,
  status      VARCHAR(24) NOT NULL,
  payload_json MEDIUMTEXT NULL,
  created_at  BIGINT NOT NULL,
  updated_at  BIGINT NOT NULL,
  PRIMARY KEY (export_id),
  KEY idx_account_export_user (app_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台数据导出';
