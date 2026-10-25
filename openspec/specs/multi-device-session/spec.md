# multi-device-session Specification

## Purpose

定义同一 Phantom ID 在手机、桌面和 Web 端的在线、漫游、状态一致性、设备管理和 PaaS 登录授权边界。

## Requirements

### Requirement: 多设备在线与同类型顶替（MD-01, MD-02）

平台 SHALL 允许手机、桌面和 Web 类型同时在线；同一设备类型的新连接 MUST 踢出旧连接并发送 `SAME_TYPE_LOGIN`。并发登录 MUST 原子处理，同一设备类型只能保留一个有效连接。

#### Scenario: 手机与桌面同时在线
- **WHEN** 用户已在手机登录后再登录桌面端
- **THEN** 两端均保持在线，消息和状态同步到全部在线设备

#### Scenario: 第二个 Web 会话登录
- **WHEN** 同一用户已有 Web 连接时建立新的 Web 连接
- **THEN** 旧 Web 连接收到 `KickPush{reason=SAME_TYPE_LOGIN}` 并断开

### Requirement: 消息漫游与全量重建（MD-03, MD-04）

平台 SHALL 永久保存会话消息并支持按会话分页漫游。新设备首次登录或同步游标超出用户事件保留窗口时，客户端 MUST 通过会话快照和消息分页重建本地状态，并从响应中的 `max_user_seq` 重置游标。

#### Scenario: 新设备拉取历史
- **WHEN** 用户在一台无本地缓存的新设备登录并打开历史会话
- **THEN** 客户端按 `conv_seq` 分页加载云端历史，顺序与旧设备一致

### Requirement: 多端消息与状态一致（MD-05, MD-06, MD-07, MD-08, MD-09, MD-10, MD-11, MD-12, MD-13）

同一账号各在线端 MUST 通过用户同步 Timeline 获得新消息、撤回、已读位置、会话状态和草稿变化。客户端 MUST 按 `user_seq` 顺序应用并以 `msg_id`、`conv_seq` 和状态版本去重。

#### Scenario: 一端撤回多端同步
- **WHEN** 用户在桌面端撤回一条消息
- **THEN** 同一账号手机端和接收方全部在线端同步显示撤回提示

### Requirement: 设备管理与远程下线（MD-14, MD-15）

用户 SHALL 能查看当前账号在线设备的类型、名称或型号、登录时间、最近活跃时间和大致地点，并 MUST 能远程下线除当前设备外的指定设备。远程下线 MUST 发送 `REMOTE_LOGOUT` 并从设备列表移除。

#### Scenario: 远程下线异常设备
- **WHEN** 用户在某设备上远程下线另一台设备
- **THEN** 目标设备收到下线通知并断开，列表不再展示该在线设备

### Requirement: 设备数量限制（MD-18）

应用 SHALL 配置单账号在线设备上限。新登录超过上限且不存在可顶替的同类型连接时，平台 MUST 拒绝连接并返回 `DEVICE_LIMIT_EXCEEDED`；默认策略 MUST 由应用配置明确，不得随机踢出设备。

#### Scenario: 超过设备上限
- **WHEN** 用户在线设备数已达到应用配置上限并尝试新增不可顶替的连接
- **THEN** 平台拒绝新连接并返回设备数量超限错误

### Requirement: PaaS 扫码授权适配（MD-16, MD-17）

平台 MUST NOT 提供终端用户扫码登录或二维码授权端点。扫码后的身份确认、UserToken 签发和桌面端凭据交付 MUST 由接入方账号体系完成；平台只接受接入方签发的有效 UserToken 建立连接。

#### Scenario: 接入方完成扫码授权
- **WHEN** 接入方在自己的手机端确认桌面扫码请求并为其签发有效 UserToken
- **THEN** 桌面端使用该 UserToken 调用 Phantom SDK 登录，平台不参与二维码状态管理

### Requirement: 在线状态与输入信令（DC-05, DC-06）

平台 SHALL 路由在线状态订阅和输入状态信令，但 MUST NOT 将 Presence/Typing 持久化为消息或同步事件。连接断开时 MUST 停止向订阅方推送该设备的实时状态。

#### Scenario: 输入状态不落库
- **WHEN** 用户发送 Typing 信令后离线
- **THEN** 接收方只短暂展示输入状态，重新登录后不会从同步 Timeline 获取该信令
