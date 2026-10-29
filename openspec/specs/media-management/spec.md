# media-management Specification

## Purpose

定义图片、语音、视频和文件的媒体上传与访问契约，支持断点续传、完整性校验、秒传、缩略图、受控访问和生命周期清理。

## Requirements

### Requirement: 分片上传与断点续传（MSG-03, MSG-04, MSG-05, MSG-06）

平台 SHALL 提供上传初始化、分片上传和完成确认接口。客户端 MUST 先获取 `upload_id` 和分片信息，再上传文件分片；重复上传同一分片 MUST 幂等。中断后使用同一文件摘要、文件名、大小、MIME、媒体类型及阅后即焚策略重新初始化 MUST 返回已上传分片，允许继续续传。平台 MUST 保留初始化时的媒体类型，不能在完成上传时根据 MIME 改写类型。

#### Scenario: 中断后恢复上传
- **WHEN** 客户端上传部分分片后断开，并以相同文件摘要、文件名、大小、MIME、媒体类型及阅后即焚策略重新初始化
- **THEN** 平台返回已上传分片列表，客户端只上传剩余分片

#### Scenario: 重复分片幂等
- **WHEN** 客户端重复上传同一上传会话的同一分片
- **THEN** 平台不产生重复分片且返回成功

### Requirement: 媒体完整性与限制（MSG-05, MSG-06, MSG-17）

平台 SHALL 在初始化时校验文件大小、MIME 和类型限制，在完成时校验分片数量与 SHA-256。所有媒体的大小上限为 500 × 1024 × 1024 字节；`VIDEO` 消息另受 100 × 1024 × 1024 字节限制，`VOICE` 时长必须为 1–60 秒。超限或格式错误的 MIME MUST 被拒绝且不创建可发送媒体。

`FILE` SHALL 支持任意文件后缀与合法的具体 MIME，不使用文件格式白名单；MIME 缺失或仅含空白时 SHALL 使用 `application/octet-stream`。非空 MIME MUST 可解析且不含通配符或控制字符。`IMAGE`、`VOICE`、`VIDEO` MUST 分别提供 `image/*`、`audio/*`、`video/*` 范围内的具体 MIME，不使用普通文件的缺省处理。

#### Scenario: 超大文件在上传前被拒绝
- **WHEN** 客户端初始化一个 600MB 文件上传
- **THEN** 平台返回媒体过大错误，客户端不上传分片

#### Scenario: 摘要不匹配
- **WHEN** 上传完成后的 SHA-256 与初始化摘要不一致
- **THEN** 平台拒绝完成上传并要求重新上传有效文件

#### Scenario: JSON 和未知格式作为文件发送
- **WHEN** 用户以 `FILE` 上传 JSON、未知后缀或无后缀文件
- **THEN** 平台接受合法 MIME，缺失 MIME 时使用 `application/octet-stream`，完成后保留文件名、原始字节与 `FILE` 类型

#### Scenario: 图片或视频作为普通附件发送
- **WHEN** 用户以 `FILE` 上传 `image/png` 或 `video/mp4` 文件
- **THEN** 平台按 500MB 文件规则处理，完成后仍为 `FILE`，不生成缩略图或执行音视频探测

### Requirement: 秒传与对象复用（MSG-03, MSG-04, MSG-05, MSG-06）

当普通上传与当前应用、当前用户已有可用且非阅后即焚对象的摘要、文件名、大小、MIME 和媒体类型均匹配时，平台 SHALL 直接返回同一 `media_id` 和访问信息。阅后即焚模式下 MUST 禁用跨消息秒传，避免清理一个对象影响其他消息。

#### Scenario: 相同文件秒传
- **WHEN** 用户上传一个已存在且摘要、文件名、大小、MIME、媒体类型均一致的普通文件
- **THEN** 平台直接返回既有 `media_id`，不重复保存文件本体

#### Scenario: 同一内容以不同媒体类型上传
- **WHEN** 已有 `IMAGE` 对象或上传会话，用户以 `FILE` 上传相同内容
- **THEN** 平台不复用 `IMAGE` 对象或会话，只匹配符合条件的 `FILE` 对象或会话；无匹配时创建新的文件上传会话

### Requirement: 媒体元数据与缩略图（MSG-03, MSG-04, MSG-05, MSG-06）

平台 SHALL 为图片生成缩略图和宽高信息，为视频提取封面、时长和尺寸，为语音保存时长，为文件保存名称、扩展名和大小。元数据 MUST 随消息内容下发且不得信任客户端伪造的完成态。

#### Scenario: 图片返回缩略图
- **WHEN** 用户成功上传一张有效图片
- **THEN** 完成响应包含 `media_id`、原图 URL、缩略图 URL、宽高和大小

### Requirement: 媒体访问控制（SEC-11）

媒体下载 URL MUST 只在有效期内可用，并与 `app_id`、用户和会话访问权限关联。未授权用户 MUST 无法通过猜测 `media_id` 访问对象；阅后即焚对象不得返回永久公共 URL。

#### Scenario: 非会话成员访问被拒绝
- **WHEN** 不属于消息所属会话的用户使用 `media_id` 请求媒体
- **THEN** 平台拒绝访问并记录越权审计

#### Scenario: 普通附件下载与代理透传
- **WHEN** 用户通过媒体接口或 Easy IM BFF 下载普通 `FILE` 附件
- **THEN** 返回原始字节和正确 MIME，设置 `Content-Disposition: attachment` 并编码原始文件名，设置 `X-Content-Type-Options: nosniff`；BFF 保留这些响应头以及缓存控制头
- **AND** 图片和缩略图预览不设置附件下载响应头

### Requirement: 媒体生命周期与焚毁（AC-08）

平台 SHALL 对未绑定消息、过期上传会话和阅后即焚媒体执行异步清理。清理 MUST 幂等且不得删除仍被有效消息引用的普通媒体；媒体已清理时消息 MUST 展示明确占位状态。

#### Scenario: 清理未被引用的上传
- **WHEN** 一个上传会话超过配置有效期仍未被消息引用
- **THEN** 平台异步删除临时分片和未引用对象，不返回错误给已完成消息
