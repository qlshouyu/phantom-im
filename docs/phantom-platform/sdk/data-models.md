# 数据模型与消息内容

## 字段、数值和标识约定

- JS SDK 导出的领域类型及 Protobuf 解码结果使用 camelCase。当前单体 HTTP JSON 使用 snake_case，SDK 多数方法原样返回 `ApiResponse.data`。
- `uploadMedia()` 返回归一化 `UploadSession`；`getMedia()` 在原字段之外补充 camelCase 别名。这两个方法不能作为其他 HTTP 方法自动转换命名的依据。
- `userId` 是 Phantom ID；业务账号 ID 应由接入方服务端映射。AppID、用户 ID、消息 ID、群 ID 和设备 ID 都按不透明字符串传递。
- `singleConvId(a, b)` 当前为 `si_` + 两个 ID 按字典序排序后用 `__` 拼接。例如 `singleConvId('u_bob', 'u_alice')` 为 `si_u_alice__u_bob`。群聊使用服务端返回的 `conv_id`，当前生成规则为 `gr_` + `groupId`。不要按早期协议注释中的 hash 规则自行实现单聊 ID。
- `serverTime`、`editedAt`、HTTP `*_at` 和同步 `eventTime` 等时间使用 Unix 毫秒；JWT `iat/exp` 使用秒。时长使用各字段标注的秒；大小为字节。
- `convSeq` 是会话内排序序号，`userSeq` 是当前用户事件流游标，二者不能互换。JS 当前转成 `number`；其他语言需使用足够范围的整数处理 Protobuf `int64/uint64`，避免经浮点转换丢失精度。
- 下方 HTTP 模型为返回值字段说明，**不是 npm 包已导出的 TypeScript 类型**。实际导出类型只有 `DeviceType`、`MessageKind`、`MessageState`、`LoginOptions`、`SendMessageInput`、`ChatMessage`、`MediaUploadOptions`、`UploadSession`，以及类 `PhantomClient`、`PhantomAuthError` 和函数 `singleConvId`。

## SDK 类型

```ts
type DeviceType = 'MOBILE' | 'DESKTOP' | 'WEB';
type MessageKind = 'TEXT' | 'EMOJI' | 'IMAGE' | 'VOICE' | 'VIDEO' | 'FILE'
  | 'LOCATION' | 'CARD' | 'MERGED' | 'SYSTEM';
type MessageState = 'sending' | 'sent' | 'delivered' | 'read'
  | 'failed' | 'recalled' | 'burned';

interface LoginOptions {
  userToken: string;
  userId: string;
  deviceId: string;
  deviceType?: DeviceType; // 默认 WEB。
}

interface SendMessageInput {
  convId: string;
  convType?: 'SINGLE' | 'GROUP'; // 默认按 gr_ 前缀判断。
  kind?: MessageKind;           // 默认 TEXT。
  content: Record<string, unknown>;
  replyToMsgId?: string;
  mentions?: string[];          // 用户 Phantom ID；ALL 表示 @全体。
  clientMsgId?: string;         // 默认 c- + crypto.randomUUID()。
}

interface ChatMessage {
  msgId: string;               // 发送中通常为空；服务端成功后回填。
  clientMsgId: string;
  convId: string;
  convType: 'SINGLE' | 'GROUP';
  senderId: string;
  kind: MessageKind;
  content: Record<string, any>;
  text: string;                // 展示摘要，不是消息内容的替代品。
  convSeq: number;              // 发送中为 0。
  serverTime: number;           // 乐观态暂用本机时间，成功后回填。
  state: MessageState;
  recalled: boolean;
  restricted: boolean;
  isRequest: boolean;
  replyToMsgId?: string;
  replySnapshot?: Record<string, any>;
  mentions: string[];
  burnAfterReadTtlSeconds: number; // 0 表示不开启。
}
```

`replySnapshot` 的 Protobuf 字段为 `senderId`、`senderName`、`msgType`、`digest`。`restricted` 表示受限投递提示，不能据此推断对方的具体关系或风控原因。`isRequest` 用于非好友消息请求分区。

`getMessages()` 返回新的数组，但数组内的对象没有深拷贝。应用不应直接修改 SDK 消息对象。

## 消息内容

`SendMessageInput.content` 是 `MsgContent` 的对象表示，必须包含与 `kind` 对应的一个分支。分支内使用 camelCase；错误拼写或下划线键可能在 Protobuf 编码时被忽略。枚举字段使用协议数值，例如 `cardType: 1`，不能使用字符串 `'SINGLE'`。

| kind / 协议值 | content 结构 | 说明 |
| --- | --- | --- |
| `TEXT` / 1 | `{ text: { text: string } }` | Unicode 表情也作为文本发送 |
| `EMOJI` / 2 | `{ emoji: { emojiId: string } }` | 内置表情 ID |
| `IMAGE` / 3 | `{ image: { mediaId, url, thumbUrl, width, height, sizeBytes } }` | 推荐通过上传后 `sendMedia()` 生成 |
| `VOICE` / 4 | `{ voice: { mediaId, url, durationS } }` | 时长为秒 |
| `VIDEO` / 5 | `{ video: { mediaId, url, coverUrl, durationS, sizeBytes, width, height } }` | 时长为秒 |
| `FILE` / 6 | `{ file: { mediaId, url, fileName, sizeBytes, ext } }` | `ext` 为协议中的扩展名字段 |
| `LOCATION` / 7 | `{ location: { latitude, longitude, poiName, address } }` | 经纬度为 double |
| `CARD` / 8 | `{ card: { cardType, targetId, snapshotName, snapshotAvatar } }` | `cardType`: 用户 1、群 2 |
| `MERGED` / 9 | `{ merged: { title, messages } }` | `messages` 是协议 `Msg[]`，不能直接传 `ChatMessage[]`；通常使用 `forward(..., true)` |
| `SYSTEM` / 10 | `{ system: { templateId, params } }` | 系统提示；`params` 为 string → string 的映射，发送权限仍由平台决定 |

示例：

```ts
await client.sendMessage({
  convId,
  kind: 'LOCATION',
  content: { location: {
    latitude: 31.2304, longitude: 121.4737,
    poiName: '会面地点', address: '应用提供的地点描述',
  } },
});

await client.sendMessage({
  convId,
  kind: 'CARD',
  content: { card: {
    cardType: 1, targetId: peerUserId,
    snapshotName: '对方昵称', snapshotAvatar: '',
  } },
});
```

`SYSTEM` 已在 JS 枚举中出现不表示第三方可以模拟全部平台事件；`CALL_RECORD` 虽在 Protobuf 预留值 11，当前 JS `MessageKind` 没有该值。

### 当前消息内容校验

服务端按 Unicode 码点计算下列字符串长度，不能简单用 JS `string.length` 代替。SDK 的开放 `Record` 参数不代表跳过平台校验。

| 类型 | 当前校验 |
| --- | --- |
| TEXT | 非空，最多 5000 个码点 |
| EMOJI | emojiId 非空白，最多 128 个码点 |
| IMAGE | mediaId 非空白且最多 64 个码点；width、height、sizeBytes 大于 0 |
| VOICE | mediaId 非空白且最多 64 个码点；durationS 为 1–60 秒 |
| VIDEO | mediaId 非空白且最多 64 个码点；durationS 为 1–300 秒；sizeBytes 大于 0 且不超过 104857600 |
| FILE | mediaId 非空白且最多 64 个码点；fileName 非空白且最多 512 个码点；sizeBytes 大于 0 且不超过 524288000 |
| LOCATION | latitude 在 -90 至 90、longitude 在 -180 至 180；poiName 非空白且最多 128 个码点 |
| CARD | cardType 为 1 或 2；targetId、snapshotName 非空白且各最多 128 个码点 |
| MERGED | title 非空白且最多 128 个码点；messages 包含 1–100 条且不能嵌套 MERGED |
| SYSTEM | templateId 非空白且最多 128 个码点；仍需满足平台其他权限校验 |

完整结构校验来源：[MessageContentValidator](../../../phantom-platform/server/phantom-services/phantom-message/src/main/java/im/phantom/message/app/MessageContentValidator.java)。上传阶段和发送阶段分别校验，上传成功不等于发送一定成功。

## 媒体上传模型

```ts
interface MediaUploadOptions {
  fileName: string;
  sizeBytes: number;
  sha256: string;
  mimeType: string;
  mediaType: 'IMAGE' | 'VOICE' | 'VIDEO' | 'FILE';
  durationSeconds?: number;
  width?: number;
  height?: number;
  burnAfterRead?: boolean;
  burnAfterReadTtlSeconds?: number;
  signal?: AbortSignal;
  onProgress?: (uploadedBytes: number, totalBytes: number) => void;
}

interface UploadSession {
  uploadId: string;
  partSize: number;
  sizeBytes: number;
  totalParts: number;
  uploadedParts: number[]; // 分片编号从 1 开始。
  instant: boolean;
  mediaId?: string;
  url?: string;
  thumbUrl?: string;
}
```

`uploadMedia()` 的 options 将 `sizeBytes`、`mimeType` 改为可选，默认使用 Blob 的 `size` 和 `type`。FILE 未给 MIME 且 Blob 无类型时回退到 `application/octet-stream`；其他媒体需要具体有效 MIME。`sha256` 由宿主计算，SDK 不代算。

`getMedia()` 的 HTTP 字段：`media_id, media_type, mime_type, size_bytes, url, thumb_url, duration_seconds, width, height, file_name, status`；额外别名：`mediaId, mediaType, mimeType, sizeBytes, thumbUrl, durationSeconds, fileName`。`uploadPart()` 返回 HTTP `{ upload_id, part_number, etag, size_bytes }`。

当前上传入口校验：文件非空且不超过 524288000 字节，视频不超过 104857600 字节，语音时长 1–60 秒。IMAGE、VOICE、VIDEO 的 MIME 分别需要 `image/`、`audio/`、`video/` 对应具体类型；FILE 使用合法 MIME。其他消息内容和业务权限限制以服务端校验为准。

## HTTP 返回模型

以下字段来自当前服务端 DTO；标记 `?` 表示可空或可能缺失，未标记不表示客户端可省略校验。对象列表仍需按接口定义读取，不能统一假设为 `{ items: [] }`。

### 会话和历史

| 模型 | 字段 |
| --- | --- |
| `ConversationPage` | `items: ConversationSnapshot[]`, `next_cursor?: string`, `has_more: boolean`, `max_user_seq: number`, `badge_count: number` |
| `ConversationSnapshot` | `conv_id`, `conv_type`（SINGLE / GROUP）, `target_id`, `title`, `avatar_url`, `last_message_digest`, `last_message_at`, `unread_count`, `mention_me`, `pinned`, `pinned_at`, `muted`, `draft`, `draft_edited_at`, `max_conv_seq`, `read_conv_seq`, `read_only` |
| `ReadPosition` | `conv_id`, `user_id`, `read_conv_seq` |
| `ConversationDraft` | `conv_id`, `draft`, `edited_at` |
| `MessagePage`（HTTP 原始页） | `items: MessageView[]`, `next_cursor: number`, `has_more: boolean`；`history()` 仅返回其中的 `items` |
| `MessageView` | `msg_id`, `client_msg_id`, `conv_id`, `conv_type`, `sender_id`, `msg_type`, `content_json: string`, `conv_seq`, `server_time`, `status`, `recalled`, `restricted`, `is_request`, `reply_to_msg_id?`, `reply_sender_id?`, `reply_sender_name?`, `reply_digest?`, `burn_after_read_ttl_seconds` |
| `MessageAction` | `msg_id`, `target_msg_ids: string[]`；用于 `forward()` 的返回 |

`MessageView.conv_type/msg_type/status` 为服务端协议枚举名称（如 `CONV_TYPE_SINGLE`、`CONV_TYPE_GROUP`、`MSG_TYPE_TEXT`、`MSG_STATUS_READ`），不是 `ChatMessage.kind/state`。`content_json` 为 Protobuf 消息内容 JSON 字符串，其分支字段按 Protobuf JSON 使用 lowerCamelCase。当前历史模型不携带完整 `mentions` 和 `ReplySnapshot.msgType`。

### 用户与关系

| 模型 | 字段 |
| --- | --- |
| `UserProfile` | `user_id`, `app_id`, `nickname`, `avatar_url`, `ext: Record<string,string>`, `status` |
| `RelationUser` | `user_id`, `nickname`, `avatar_url` |
| `FriendSummary` | `user_id`, `nickname`, `avatar_url`, `display_name`, `remark`, `pinned`, `group_ids: string[]` |
| `FriendRequestPage` | `items: FriendRequest[]`, `unread_count` |
| `FriendRequest` | `request_id`, `from_user_id`, `to_user_id`, `peer: RelationUser`, `verification_message`, `status`, `direction`, `created_at`, `handled_at?`, `unread` |
| `FriendRequestResult` | `request_id`, `status`, `auto_accepted` |
| `FriendGroup` | `group_id`, `name`, `member_count` |
| `BlacklistEntry` | `user_id`, `nickname`, `avatar_url`, `created_at` |
| `QrCard` | `card_id`, `token`, `card_url`, `expires_at?` |
| `QrCardScanResult` | `user: UserCard` |
| `UserCard` | `user_id`, `nickname`, `avatar_url`, `remark`, `friend`, `blocked_by_me` |

### 群、设备和设置

| 模型 | 字段 |
| --- | --- |
| `GroupSummary` | `group_id`, `conv_id`, `name`, `avatar_url`, `member_count`, `owner_id`, `status` |
| `GroupDetail` | `summary: GroupSummary`, `announcement: Announcement`, `member_invite_enabled`, `announcement_unread` |
| `GroupMember` | `group_id`, `user_id`, `role`（OWNER / ADMIN / MEMBER）, `group_nickname`, `muted_until?`, `muted_all` |
| `Announcement` | `group_id`, `content`, `operator_id`, `version` |
| `DeviceInfo` | `device_id`, `device_type`（数值 1 / 2 / 3）, `platform`, `device_name`, `login_at`, `last_active_at`, `location`, `foreground` |
| `PushSettings` | `push_enabled`, `preview_enabled`, `privacy_policy_url`, `terms_url`；隐私链接接口也返回此结构 |

### 搜索与合规

| 模型 | 字段 |
| --- | --- |
| `SearchResult` | `contacts: ContactHit[]`, `groups: GroupHit[]`, `messages: MessageHit[]`, `contact_total`, `group_total`, `message_total` |
| `SearchSuggestions` | `contacts: ContactHit[]`, `groups: GroupHit[]`, `total` |
| `ConversationSearch` | `conv_id`, `messages: MessageHit[]`, `total` |
| `ContactHit` | `user_id`, `nickname`, `remark`, `avatar_url` |
| `GroupHit` | `group_id`, `conv_id`, `name`, `avatar_url` |
| `MessageHit` | `msg_id`, `conv_id`, `conv_title`, `sender_id`, `digest`, `conv_seq`, `server_time`, `recalled`, `burned` |
| `Report` | `report_id`, `app_id`, `reporter_id`, `target_type`, `target_id`, `reason`, `description`, `snapshot_json`, `status`, `created_at`, `updated_at` |
| `Appeal` | `appeal_id`, `sanction_id`, `user_id`, `content`, `status`, `result`, `created_at`, `updated_at` |
| `AccountExport` | `export_id`, `app_id`, `user_id`, `status`, `payload_json`, `created_at`, `updated_at` |

防抖搜索丢弃旧响应时的本地占位对象例外：`searchDebounced()` 可能返回 `{ contacts: [], groups: [], messages: [], contactTotal: 0, groupTotal: 0, messageTotal: 0, stale: true }`，联想搜索可能返回 `{ contacts: [], groups: [], total: 0, stale: true }`，旧请求失败可能得到 `null`。应用应先检查 `stale` / `null`，再读取结果。

## Protobuf 事件模型

事件回调参数中枚举为数值，64 位整数可能为 protobufjs 的 `Long`；`ChatMessage` 中相关字段才被 SDK 转为 `number`。如需比较原始事件序号，应显式处理整数表示。

| 事件对象 | 主要字段 |
| --- | --- |
| `TypingPush` | `convId`, `userId`, `typing` |
| `PresenceInfo` | `userId`, `status`（1 ONLINE、2 AWAY、3 OFFLINE）, `lastActiveTime` |
| `SyncEvent` | `userSeq`, `eventTime`，以及下列事件分支之一 |
| `SyncEvent` 分支 | `newMessage`, `recall`, `readPosition`, `readReceipt`, `convState`, `draft`, `relation`, `group`, `profile`, `restrictedDelivery`, `msgStatus`, `report` |
| `RelationEvent` | `type`, `peerId`, `peer`, `requestId`, `verificationMessage`, `createdAt`, `groupIds`, `pinned`, `remark`, `groupId`, `groupName`, `groupDeleted` |
| `GroupEvent` | `type`, `groupId`, `targetUserIds`, `operatorId`, `params` |
| `ProfileEvent` | `type`, `targetId`, `changed` |

各分支的完整字段与枚举值以 [sync.proto](../../../phantom-platform/contracts/proto/phantom/v1/sync.proto)、[message.proto](../../../phantom-platform/contracts/proto/phantom/v1/message.proto)和 [signaling.proto](../../../phantom-platform/contracts/proto/phantom/v1/signaling.proto)为准。收到 `report` 等没有专属 SDK 事件的分支时，通过 `sync` 监听。
