# Phantom IM MVP HTTP 路由清单

> 对应变更：`implement-mvp-p0-p1`。所有响应统一使用 `ApiResponse<T>`。

| 领域 | 方法 | 路径 | 用途 |
|---|---|---|---|
| 应用 | POST | `/openapi/apps` | 创建应用并返回一次性 AppSecret |
| 应用 | GET | `/openapi/apps/{appId}` | 查询应用详情 |
| 应用 | POST | `/openapi/apps/{appId}/secrets/rotate` | 轮换 AppSecret |
| 应用 | GET/PUT | `/openapi/apps/{appId}/config` | 查询或更新应用级功能配置 |
| 用户 | POST | `/openapi/users` | 导入用户并返回 Phantom ID |
| 用户 | GET/PUT/DELETE | `/openapi/users/{userId}` | 查询、更新或删除用户 |
| 用户 | POST | `/openapi/users/{userId}/disable` | 禁用用户并踢出全部设备 |
| 用户 | POST | `/openapi/users/{userId}/restore` | 恢复用户 |
| 用户 | POST | `/openapi/users/{userId}/force-logout` | 强制指定设备或全部设备下线 |
| 用户 | GET/PUT | `/users/me` | 当前用户资料读写 |
| 用户 | GET | `/users/{userId}` | 查询用户资料卡 |
| 关系 | GET | `/friends` | 好友列表（特别关心置顶，再按展示名排序） |
| 关系 | PUT/DELETE | `/friends/{friendId}` | 更新备注/分组或单向删除好友 |
| 关系 | GET/PUT | `/friends/settings` | 自动通过申请等关系设置 |
| 关系 | GET/POST | `/friends/requests` | 好友申请列表与创建 |
| 关系 | POST | `/friends/requests/{requestId}` | 通过、拒绝或忽略好友申请 |
| 关系 | GET/POST | `/friends/groups` | 好友分组列表与创建 |
| 关系 | PUT/DELETE | `/friends/groups/{groupId}` | 重命名或删除好友分组 |
| 关系 | GET/POST/DELETE | `/blacklist` | 黑名单管理 |
| 关系 | GET | `/friends/qr` | 获取或创建当前用户二维码名片 |
| 关系 | POST | `/friends/qr/refresh` | 刷新二维码并让旧码失效 |
| 关系 | POST | `/friends/qr/scan` | 扫描二维码并解析资料卡 |
| 群组 | GET/POST | `/groups` | 群列表与建群 |
| 群组 | GET/PUT/DELETE | `/groups/{groupId}` | 群资料、转让与解散 |
| 群组 | GET/POST/DELETE | `/groups/{groupId}/members` | 成员列表、邀请与移出 |
| 群组 | POST | `/groups/{groupId}/invites` | 生成群二维码或邀请链接 |
| 群组 | GET/PUT | `/groups/{groupId}/announcement` | 群公告 |
| 群组 | POST/DELETE | `/groups/{groupId}/mutes` | 成员禁言与全员禁言 |
| 群组 | PUT | `/groups/{groupId}/members/{userId}/role` | 任命或取消管理员 |
| 群组 | PUT | `/groups/{groupId}/members/{userId}/nickname` | 修改群名片 |
| 群组 | POST | `/groups/{groupId}/transfer` | 转让群主 |
| 群组 | DELETE | `/groups/{groupId}/leave` | 主动退群 |
| 群组 | POST | `/groups/join` | 通过邀请链接申请或加入 |
| 群组 | POST | `/groups/{groupId}/join-requests/{requestId}` | 处理入群申请 |
| 会话 | GET | `/conversations` | 会话列表快照 |
| 会话 | GET/POST/DELETE | `/conversations/{convId}` | 会话详情、状态写入与删除 |
| 会话 | GET | `/conversations/{convId}/messages` | 历史消息漫游 |
| 会话 | GET/PUT | `/conversations/{convId}/read-positions` | 已读位置查询与上报 |
| 会话 | PUT/DELETE | `/conversations/{convId}/draft` | 草稿保存与清空 |
| 会话 | GET | `/conversations/{convId}/search` | 会话内搜索 |
| 会话 | POST | `/conversations/{convId}/request` | 接受或拒绝消息请求 |
| 媒体 | POST | `/media/upload/init` | 初始化分片上传 |
| 媒体 | PUT | `/media/upload/{uploadId}/part/{partNumber}` | 上传媒体分片 |
| 媒体 | POST | `/media/upload/{uploadId}/complete` | 完成上传并获取 media_id |
| 媒体 | GET | `/media/{mediaId}` | 获取受控下载地址 |
| 媒体 | GET | `/media/{mediaId}/content` | 使用短时签名下载媒体 |
| 设备 | GET | `/devices` | 在线设备列表 |
| 设备 | POST | `/devices/{deviceId}/logout` | 远程下线指定设备 |
| 推送 | POST/DELETE | `/push/tokens` | 注册或注销推送 Token |
| 推送 | GET/PUT | `/push/settings` | 推送总开关与预览开关 |
| 搜索 | GET | `/search` | 联系人、群组和聊天记录全局搜索 |
| 搜索 | GET | `/search/suggest` | 联系人与群组联想搜索 |
| 搜索 | POST | `/search/rebuild` | 显式重建当前用户的搜索索引 |
| 合规 | POST/GET | `/reports` | 提交或查询举报 |
| 合规 | POST/GET | `/appeals` | 提交或查询申诉 |
| 合规 | POST/GET | `/account/export` | 申请或下载平台数据导出 |
| 合规 | GET/PUT | `/account/privacy-links` | 接入方隐私政策与用户协议链接配置 |
| 合规 | POST | `/account/minor-mode` | 开启或关闭未成年人模式 |
| 合规 | POST | `/openapi/risk/sanctions` | 管理处置 |
| 合规 | POST | `/openapi/risk/appeals` | 解除处置 |
| 合规 | GET | `/openapi/risk/audit` | 查询合规审计 |
| 合规 | GET/POST | `/openapi/risk/reports` | 查询待处理举报与提交处理结果 |
| 消息 | POST | `/messages/forward` | 单条或合并转发 |
| 消息 | POST | `/messages/{msgId}/recall` | 撤回消息 |
| 消息 | DELETE | `/messages/{msgId}` | 本地删除消息 |
| 会话 | DELETE | `/conversations/{convId}/messages` | 本地清空聊天记录 |

## 统一响应

```json
{
  "code": 0,
  "message": "ok",
  "data": {},
  "request_id": "req_xxx"
}
```

## 端上 HTTP 鉴权

`/users/*`、`/friends/*`、`/blacklist/*`、`/conversations/*` 必须携带：

- `Authorization: Bearer <UserToken>`
- `X-App-Id: <AppID>`
- `X-User-Id: <Phantom ID>`

服务端按应用和用户状态校验 UserToken 后，才允许进入业务接口。
