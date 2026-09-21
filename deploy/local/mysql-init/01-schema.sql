-- Phantom IM M0 建表 DDL（幂等）
-- 对应 design.md D2：会话 Timeline（永久）+ 用户同步 Timeline（30 天滚动）+ 取号真相源水位
CREATE DATABASE IF NOT EXISTS phantom_im DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE phantom_im;

-- 会话 Timeline：完整消息体，永久存储，支撑漫游分页（MD-03）
-- 唯一键 (sender_id, client_msg_id) 直接承载发送幂等（single-chat-messaging spec）
CREATE TABLE IF NOT EXISTS conv_timeline (
  conv_id       VARCHAR(128)  NOT NULL COMMENT '会话ID：单聊 si_+hash，群聊 gr_+groupId',
  conv_seq      BIGINT        NOT NULL COMMENT '会话内严格递增序号（MSG-15）',
  msg_id        VARCHAR(64)   NOT NULL COMMENT '服务端全局唯一消息ID',
  client_msg_id VARCHAR(64)   NOT NULL COMMENT '客户端幂等键（MSG-16）',
  sender_id     VARCHAR(64)   NOT NULL COMMENT '发送者 Phantom ID',
  conv_type     INT           NOT NULL COMMENT 'ConvType 枚举值',
  msg_type      INT           NOT NULL COMMENT 'MsgType 枚举值',
  msg_bytes     MEDIUMBLOB    NOT NULL COMMENT '序列化的 Msg proto',
  server_time   BIGINT        NOT NULL COMMENT '服务端时间（毫秒）',
  recalled      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否已撤回（M0 恒 0）',
  PRIMARY KEY (conv_id, conv_seq),
  UNIQUE KEY uk_sender_client_msg (sender_id, client_msg_id),
  KEY idx_msg_id (msg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话 Timeline';

-- 用户同步 Timeline：轻量事件（M0 仅 NewMessageEvent，内联消息体）
-- 唯一键 (user_id, msg_id) 承载扇出幂等（message-fanout-delivery spec）
CREATE TABLE IF NOT EXISTS user_timeline (
  user_id     VARCHAR(64)  NOT NULL COMMENT '接收者 Phantom ID',
  user_seq    BIGINT       NOT NULL COMMENT '用户同步 Timeline 编号（MD-05）',
  event_type  INT          NOT NULL COMMENT 'SyncEvent 类型判别（10=new_message ...）',
  msg_id      VARCHAR(64)  NULL COMMENT '消息类事件的 msg_id，用于扇出幂等',
  event_bytes MEDIUMBLOB   NOT NULL COMMENT '序列化的 SyncEvent proto',
  event_time  BIGINT       NOT NULL COMMENT '事件时间（毫秒）',
  PRIMARY KEY (user_id, user_seq),
  UNIQUE KEY uk_user_msg (user_id, msg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户同步 Timeline';

-- 取号真相源水位：Redis 冷启动/丢数据时对齐用（sequence-allocation spec）
-- scope 形如 'conv:si_xxx' / 'user:u1001'
CREATE TABLE IF NOT EXISTS seq_watermark (
  scope    VARCHAR(160) NOT NULL COMMENT '取号域：conv:{conv_id} 或 user:{user_id}',
  seq      BIGINT       NOT NULL COMMENT '已落库的最大序号（真相源）',
  PRIMARY KEY (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='取号真相源水位';
