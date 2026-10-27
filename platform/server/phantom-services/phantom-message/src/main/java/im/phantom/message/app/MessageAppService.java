package im.phantom.message.app;

import im.phantom.common.BizException;
import im.phantom.common.ConvIds;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.api.relation.RelationService;
import im.phantom.api.relation.DirectMessagePolicy;
import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.api.message.ForwardMessageRequest;
import im.phantom.api.message.MessageActionDto;
import im.phantom.api.message.MessagePageDto;
import im.phantom.api.message.MessageService;
import im.phantom.api.message.MessageViewDto;
import im.phantom.api.group.GroupService;
import im.phantom.common.risk.ContentModerationProvider;
import im.phantom.common.risk.ModerationDecision;
import im.phantom.common.risk.ModerationRequest;
import im.phantom.common.risk.UserSafetyChecker;
import im.phantom.infra.mq.Topics;
import im.phantom.infra.mq.TxMqProducer;
import im.phantom.infra.observability.PlatformMetrics;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.message.infra.ConvTimelineDao;
import im.phantom.message.MessagePublishProperties;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgStatus;
import im.phantom.proto.v1.MsgStatusEvent;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.MergedContent;
import im.phantom.proto.v1.RecallEvent;
import im.phantom.proto.v1.ReplySnapshot;
import im.phantom.proto.v1.SendMsgReq;
import im.phantom.proto.v1.SendMsgResp;
import im.phantom.proto.v1.SyncEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import io.micrometer.core.instrument.Timer;

/**
 * 收单用例（single-chat-messaging spec）：幂等 → 取号 → 落会话 Timeline → 事务消息。
 */
@Service
public class MessageAppService implements MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageAppService.class);
    private final ConvTimelineDao dao;
    private final SeqAllocator seqAllocator;
    private final TxMqProducer producer;
    private final ObjectProvider<RelationService> relationServices;
    private final ObjectProvider<AppConfigProvider> configProviders;
    private final MessageEventWriter eventWriter;
    private final ObjectProvider<GroupService> groupServices;
    private final ObjectProvider<ContentModerationProvider> moderationProviders;
    private final ObjectProvider<UserSafetyChecker> safetyCheckers;
    private final PlatformMetrics metrics;
    private final MessagePublishProperties publishProperties;
    private final MessagePersistenceService persistenceService;
    private static final com.google.protobuf.util.JsonFormat.Printer JSON =
            com.google.protobuf.util.JsonFormat.printer().omittingInsignificantWhitespace();
    private static final long RECALL_WINDOW_MS = 2L * 60L * 1000L;

    @Autowired
    public MessageAppService(ConvTimelineDao dao, SeqAllocator seqAllocator, TxMqProducer producer,
                             ObjectProvider<RelationService> relationServices,
                             ObjectProvider<AppConfigProvider> configProviders,
                             MessageEventWriter eventWriter,
                             ObjectProvider<GroupService> groupServices,
                             ObjectProvider<ContentModerationProvider> moderationProviders,
                             ObjectProvider<UserSafetyChecker> safetyCheckers,
                             PlatformMetrics metrics,
                             MessagePublishProperties publishProperties,
                             MessagePersistenceService persistenceService) {
        this.dao = dao;
        this.seqAllocator = seqAllocator;
        this.producer = producer;
        this.relationServices = relationServices;
        this.configProviders = configProviders;
        this.eventWriter = eventWriter;
        this.groupServices = groupServices;
        this.moderationProviders = moderationProviders;
        this.safetyCheckers = safetyCheckers;
        this.metrics = metrics;
        this.publishProperties = publishProperties == null ? new MessagePublishProperties() : publishProperties;
        this.persistenceService = persistenceService;
    }

    MessageAppService(ConvTimelineDao dao, SeqAllocator seqAllocator, TxMqProducer producer,
                      ObjectProvider<RelationService> relationServices,
                      ObjectProvider<AppConfigProvider> configProviders,
                      MessageEventWriter eventWriter,
                      ObjectProvider<GroupService> groupServices,
                      ObjectProvider<ContentModerationProvider> moderationProviders,
                      ObjectProvider<UserSafetyChecker> safetyCheckers,
                      PlatformMetrics metrics) {
        this(dao, seqAllocator, producer, relationServices, configProviders, eventWriter, groupServices,
                moderationProviders, safetyCheckers, metrics, new MessagePublishProperties(), null);
    }

    MessageAppService(ConvTimelineDao dao, SeqAllocator seqAllocator, TxMqProducer producer,
                      ObjectProvider<RelationService> relationServices,
                      ObjectProvider<AppConfigProvider> configProviders,
                      MessageEventWriter eventWriter,
                      ObjectProvider<GroupService> groupServices,
                      ObjectProvider<ContentModerationProvider> moderationProviders,
                      ObjectProvider<UserSafetyChecker> safetyCheckers) {
        this(dao, seqAllocator, producer, relationServices, configProviders, eventWriter, groupServices,
                moderationProviders, safetyCheckers, null);
    }

    MessageAppService(ConvTimelineDao dao, SeqAllocator seqAllocator, TxMqProducer producer) {
        this(dao, seqAllocator, producer, null, null, null, null, null, null, null);
    }

    public SendMsgResp handleSend(String senderId, SendMsgReq req) throws Exception {
        return handleSend(null, senderId, req);
    }

    public SendMsgResp handleSend(String appId, String senderId, SendMsgReq req) throws Exception {
        long started = System.nanoTime();
        try {
            SendMsgResp response = doHandleSend(appId, senderId, req);
            if (metrics != null) {
                metrics.recordMessageStage("send_total", "success", System.nanoTime() - started);
            }
            return response;
        } catch (Exception error) {
            if (metrics != null) {
                metrics.recordMessageStage("send_total", "failure", System.nanoTime() - started);
            }
            throw error;
        }
    }

    private SendMsgResp doHandleSend(String appId, String senderId, SendMsgReq req) throws Exception {
        String convId = req.getConvId();
        boolean single = ConvIds.isSingle(convId);
        boolean group = ConvIds.isGroup(convId);
        if (!single && !group) {
            throw new BizException(ErrorCodes.CONV_INVALID, "非法 conv_id: " + convId);
        }
        AppFeatureConfigDto config = timed("config", () -> loadConfig(appId));
        timed("basic_validation", () -> {
            MessageContentValidator.validate(req);
            validateMediaReferences(appId, senderId, req);
            return null;
        });
        ModerationReview moderationReview;
        if (config.riskCheckEnabled()) {
            moderationReview = timed("risk", () -> {
                assertCanSend(appId, senderId, convId);
                return moderate(appId, senderId, req);
            });
        } else {
            moderationReview = null;
            if (metrics != null) {
                metrics.recordMessageStageSkipped("risk", "disabled");
            }
        }
        ConvType expectedType = single ? ConvType.CONV_TYPE_SINGLE : ConvType.CONV_TYPE_GROUP;
        DirectMessagePolicy policy = timed("relation", () -> {
            DirectMessagePolicy evaluated = single
                    ? evaluateDirectMessage(appId, senderId, convId, config.relationHosted())
                    : DirectMessagePolicy.ALLOWED;
            if (single) {
                authorizeSingleType(req);
            } else {
                authorizeGroupMessage(appId, senderId, convId, req);
            }
            return evaluated;
        });

        // 1) 发送幂等：命中即返回首次结果（覆盖超时重发的常见顺序场景）
        ConvTimelineDao.Existing existing = timed("idempotency",
                () -> dao.findByClientMsgId(senderId, req.getClientMsgId()));
        if (existing != null) {
            return respOf(existing);
        }

        // 2) 取号（会话粒度严格递增）
        long convSeq = timed("seq", () -> seqAllocator.nextConvSeq(convId));
        String msgId = Ids.newMsgId();
        long serverTime = System.currentTimeMillis();

        Msg.Builder message = Msg.newBuilder()
                .setMsgId(msgId)
                .setClientMsgId(req.getClientMsgId())
                .setConvId(convId)
                .setConvType(req.getConvType() == ConvType.CONV_TYPE_UNSPECIFIED ? expectedType : req.getConvType())
                .setSenderId(senderId)
                .setMsgType(req.getMsgType())
                .setContent(req.getContent())
                .setConvSeq(convSeq)
                .setServerTime(serverTime)
                .setStatus(MsgStatus.MSG_STATUS_SENT)
                .setRestricted(policy.restricted())
                .setIsRequest(policy.messageRequest())
                .addAllMentions(req.getMentionsList())
                .setBurnAfterReadTtlSeconds(burnTtl(config));
        applyReply(req, convId, message);
        Msg msg = message.build();

        // 3) 本地事务写 Timeline/扩展/水位；outbox 模式同时写新消息事件并立即返回。
        boolean committed;
        if (publishProperties.outboxEnabled()) {
            if (persistenceService == null) {
                throw new BizException(ErrorCodes.SERVER_ERROR, "消息 Outbox 持久化不可用");
            }
            try {
                committed = timed("local_tx", () -> {
                    persistenceService.persistWithOutbox(msg);
                    return true;
                });
            } catch (DuplicateKeyException duplicate) {
                committed = false;
            }
        } else {
            committed = timed("local_tx", () -> producer.sendInTransaction(
                    Topics.MSG_EVENT, Topics.TAG_NEW_MSG, convId, msgId, msg.toByteArray(),
                    () -> {
                        if (persistenceService != null) {
                            persistenceService.persistTransactionMq(msg);
                        } else {
                            dao.insert(msg);
                        }
                        return true;
                    }));
        }

        if (!committed) {
            // 幂等竞态：唯一键拦截了本次插入，回查已 rollback；返回首次成功结果
            ConvTimelineDao.Existing e2 = timed("idempotency_recheck",
                    () -> dao.findByClientMsgId(senderId, req.getClientMsgId()));
            if (e2 != null) {
                return respOf(e2);
            }
            throw new BizException(ErrorCodes.SERVER_ERROR, "消息未提交");
        }

        log.info("收单成功 sender={} conv={} convSeq={} msgId={} clientMsgId={} publishMode={}",
                senderId, convId, convSeq, msgId, req.getClientMsgId(), publishProperties.getPublishMode());
        if (moderationReview != null) {
            CompletableFuture.runAsync(() -> reviewModeration(moderationReview, msgId));
        }
        return SendMsgResp.newBuilder()
                .setMsgId(msgId).setConvSeq(convSeq).setServerTime(serverTime).build();
    }

    private <T> T timed(String stage, StageAction<T> action) throws Exception {
        Timer.Sample sample = metrics == null ? null : metrics.startMessageStage();
        try {
            T value = action.run();
            if (sample != null) {
                metrics.recordMessageStage(sample, stage, "success");
            }
            return value;
        } catch (Exception error) {
            if (sample != null) {
                metrics.recordMessageStage(sample, stage, "failure");
            }
            throw error;
        }
    }

    @FunctionalInterface
    private interface StageAction<T> {
        T run() throws Exception;
    }

    private DirectMessagePolicy evaluateDirectMessage(String appId, String senderId, String convId,
                                                      boolean relationHosted) {
        String[] participants = ConvIds.participants(convId);
        String receiverId = senderId.equals(participants[0]) ? participants[1]
                : senderId.equals(participants[1]) ? participants[0] : null;
        if (receiverId == null) {
            throw new BizException(ErrorCodes.CONV_INVALID, "发送者不在会话中");
        }
        RelationService relations = relationServices == null ? null : relationServices.getIfAvailable();
        if (relations != null) {
            DirectMessagePolicy policy = relations.evaluateDirectMessage(
                    appId, senderId, receiverId, relationHosted);
            if (policy != null) {
                return policy;
            }
        }
        return DirectMessagePolicy.ALLOWED;
    }

    private void authorizeSingleType(SendMsgReq req) {
        if (req.getConvType() != ConvType.CONV_TYPE_UNSPECIFIED
                && req.getConvType() != ConvType.CONV_TYPE_SINGLE) {
            throw new BizException(ErrorCodes.CONV_INVALID, "会话类型与 conv_id 不匹配");
        }
    }

    private void authorizeGroupMessage(String appId, String senderId, String convId, SendMsgReq req) {
        if (req.getConvType() != ConvType.CONV_TYPE_UNSPECIFIED
                && req.getConvType() != ConvType.CONV_TYPE_GROUP) {
            throw new BizException(ErrorCodes.CONV_INVALID, "会话类型与 conv_id 不匹配");
        }
        GroupService groups = groupServices == null ? null : groupServices.getIfAvailable();
        if (groups == null) {
            throw new BizException(ErrorCodes.GROUP_DISSOLVED, "群服务不可用");
        }
        String groupId = groups.findGroupIdByConvId(convId, senderId);
        groups.authorizeMessage(appId, senderId, groupId, req.getMentionsList());
    }

    private void applyReply(SendMsgReq req, String convId, Msg.Builder message) {
        if (req.getReplyToMsgId().isBlank()) {
            return;
        }
        Msg original = dao.findByMsgId(req.getReplyToMsgId());
        if (original == null || !convId.equals(original.getConvId())) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "引用消息不存在或不属于当前会话");
        }
        message.setReplyToMsgId(original.getMsgId())
                .setReplySnapshot(ReplySnapshot.newBuilder()
                        .setSenderId(original.getSenderId())
                        .setSenderName(dao.displayName(original.getSenderId()))
                        .setMsgType(original.getMsgType())
                        .setDigest(digest(original))
                        .build());
    }

    private void validateMediaReferences(String appId, String senderId, SendMsgReq req) {
        for (String mediaId : requestMediaIds(req)) {
            if (!dao.ownsReadyMedia(appId, senderId, mediaId)) {
                throw new BizException(ErrorCodes.INVALID_PARAM, "媒体不存在、未完成或无权使用");
            }
        }
    }

    private List<String> requestMediaIds(SendMsgReq req) {
        MsgContent content = req.getContent();
        return switch (req.getMsgType()) {
            case MSG_TYPE_IMAGE -> List.of(content.getImage().getMediaId());
            case MSG_TYPE_VOICE -> List.of(content.getVoice().getMediaId());
            case MSG_TYPE_VIDEO -> List.of(content.getVideo().getMediaId());
            case MSG_TYPE_FILE -> List.of(content.getFile().getMediaId());
            default -> List.of();
        };
    }

    private ModerationReview moderate(String appId, String senderId, SendMsgReq req) {
        ContentModerationProvider provider = moderationProviders == null
                ? null : moderationProviders.getIfAvailable();
        if (provider == null) return null;
        String text = req.getMsgType() == MsgType.MSG_TYPE_TEXT
                ? req.getContent().getText().getText() : null;
        String mediaId = requestMediaIds(req).stream().findFirst().orElse(null);
        String mime = mediaId == null ? null : dao.mediaMimeType(mediaId);
        var decision = provider.moderate(new ModerationRequest(appId, senderId,
                req.getMsgType().name(), text, mediaId, mime));
        if (!decision.allowed()) {
            throw new BizException(ErrorCodes.CONTENT_BLOCKED, "消息内容不符合平台规范");
        }
        return decision.reviewRequired()
                ? new ModerationReview(provider, new ModerationRequest(appId, senderId,
                        req.getMsgType().name(), text, mediaId, mime)) : null;
    }

    private void reviewModeration(ModerationReview review, String msgId) {
        try {
            var decision = review.provider().review(review.request());
            if (decision.isPresent() && !decision.get().allowed()) {
                recallByModeration(msgId);
                log.info("内容异步复审拦截 msgId={} reason={}", msgId, decision.get().reasonCode());
            } else {
                log.info("内容异步复审通过 msgId={}", msgId);
            }
        } catch (RuntimeException error) {
            log.warn("内容异步复审失败 msgId={}: {}", msgId, error.toString());
        }
    }

    private void recallByModeration(String msgId) {
        Msg original = dao.findByMsgId(msgId);
        if (original == null || original.getRecalled()) return;
        long now = System.currentTimeMillis();
        Msg recalled = original.toBuilder().setRecalled(true).build();
        if (!dao.markRecalled(recalled, now)) return;
        for (String participant : participants(original.getConvId())) {
            append(participant, SyncEvent.newBuilder().setEventTime(now)
                    .setRecall(RecallEvent.newBuilder().setMsgId(msgId)
                            .setConvId(original.getConvId()).setOperatorId("system:moderation")));
        }
    }

    private void assertCanSend(String appId, String senderId, String convId) {
        UserSafetyChecker checker = safetyCheckers == null ? null : safetyCheckers.getIfAvailable();
        if (checker == null) return;
        checker.assertCanSend(appId, senderId);
        if (ConvIds.isSingle(convId)) {
            String[] members = ConvIds.participants(convId);
            String receiver = members[0].equals(senderId) ? members[1] : members[0];
            checker.assertCanReceive(appId, senderId, receiver);
        }
    }

    private AppFeatureConfigDto loadConfig(String appId) {
        if (appId == null) {
            return AppFeatureConfigDto.defaults();
        }
        AppConfigProvider configs = configProviders == null ? null : configProviders.getIfAvailable();
        if (configs == null) {
            return AppFeatureConfigDto.defaults();
        }
        return configs.getConfig(appId);
    }

    private int burnTtl(AppFeatureConfigDto config) {
        return config.burnAfterReadEnabled() ? Math.max(0, config.burnAfterReadTtlSeconds()) : 0;
    }

    private String digest(Msg msg) {
        MsgContent content = msg.getContent();
        return switch (msg.getMsgType()) {
            case MSG_TYPE_TEXT -> truncate(content.getText().getText());
            case MSG_TYPE_EMOJI -> "[表情]";
            case MSG_TYPE_IMAGE -> "[图片]";
            case MSG_TYPE_VOICE -> "[语音]";
            case MSG_TYPE_VIDEO -> "[视频]";
            case MSG_TYPE_FILE -> "[文件] " + content.getFile().getFileName();
            case MSG_TYPE_LOCATION -> "[位置] " + content.getLocation().getPoiName();
            case MSG_TYPE_CARD -> "[名片] " + content.getCard().getSnapshotName();
            case MSG_TYPE_MERGED -> "[合并转发] " + content.getMerged().getTitle();
            case MSG_TYPE_SYSTEM -> "[系统消息]";
            default -> "[消息]";
        };
    }

    private String truncate(String text) {
        String value = text == null ? "" : text.strip();
        return value.codePointCount(0, value.length()) <= 120
                ? value : value.substring(0, value.offsetByCodePoints(0, 120)) + "…";
    }

    private record ModerationReview(ContentModerationProvider provider, ModerationRequest request) {}

    private SendMsgResp respOf(ConvTimelineDao.Existing e) {
        return SendMsgResp.newBuilder()
                .setMsgId(e.msgId()).setConvSeq(e.convSeq()).setServerTime(e.serverTime()).build();
    }

    @Override
    public MessagePageDto history(String userId, String convId, long beforeConvSeq, int limit) {
        if (!canAccess(userId, convId)) {
            throw new BizException(ErrorCodes.CONV_INVALID, "无权访问该会话");
        }
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        long cursor = beforeConvSeq <= 0 ? Long.MAX_VALUE : beforeConvSeq;
        List<Msg> rows = dao.historyForUser(userId, convId, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Msg> page = hasMore ? new ArrayList<>(rows.subList(0, pageSize)) : rows;
        List<MessageViewDto> items = page.stream().map(this::view).toList();
        long nextCursor = page.isEmpty() ? 0 : page.get(0).getConvSeq();
        return new MessagePageDto(items, nextCursor, hasMore);
    }

    @Override
    @Transactional
    public MessageActionDto recall(String userId, String msgId) {
        Msg original = dao.findByMsgId(msgId);
        if (original == null) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "消息不存在");
        }
        if (!original.getSenderId().equals(userId)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "只能撤回自己发送的消息");
        }
        if (original.getRecalled()) {
            throw new BizException(ErrorCodes.ALREADY_RECALLED, "消息已撤回");
        }
        long now = System.currentTimeMillis();
        if (now - original.getServerTime() > RECALL_WINDOW_MS) {
            throw new BizException(ErrorCodes.RECALL_TIMEOUT, "消息已超过 2 分钟撤回时限");
        }
        Msg recalled = original.toBuilder().setRecalled(true).build();
        if (!dao.markRecalled(recalled, now)) {
            throw new BizException(ErrorCodes.ALREADY_RECALLED, "消息已撤回");
        }
        for (String participant : participants(original.getConvId())) {
            append(participant, SyncEvent.newBuilder().setEventTime(now)
                    .setRecall(RecallEvent.newBuilder().setMsgId(msgId)
                            .setConvId(original.getConvId()).setOperatorId(userId)));
        }
        return new MessageActionDto(msgId, List.of());
    }

    @Override
    public MessageActionDto forward(String appId, String userId, ForwardMessageRequest request) {
        if (request == null || request.msgIds() == null || request.msgIds().isEmpty()
                || request.targetConvIds() == null || request.targetConvIds().isEmpty()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "转发消息和目标会话不能为空");
        }
        Set<String> uniqueSourceIds = new LinkedHashSet<>(request.msgIds());
        if (uniqueSourceIds.size() > 100) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "合并转发最多 100 条");
        }
        List<Msg> sources = new ArrayList<>();
        for (String msgId : uniqueSourceIds) {
            Msg source = dao.findByMsgId(msgId);
            if (source == null || !canAccess(userId, source.getConvId())) {
                throw new BizException(ErrorCodes.INVALID_PARAM, "待转发消息不存在或无权访问");
            }
            if (source.getMsgType() == MsgType.MSG_TYPE_MERGED && Boolean.TRUE.equals(request.merged())) {
                throw new BizException(ErrorCodes.INVALID_PARAM, "合并消息不可再次合并");
            }
            sources.add(source);
        }
        List<String> created = new ArrayList<>();
        for (String targetConvId : new LinkedHashSet<>(request.targetConvIds())) {
            try {
                SendMsgReq.Builder req = SendMsgReq.newBuilder()
                        .setClientMsgId(Ids.newId("c"))
                        .setConvId(targetConvId)
                        .setConvType(ConvIds.isSingle(targetConvId)
                                ? ConvType.CONV_TYPE_SINGLE : ConvType.CONV_TYPE_GROUP);
                if (Boolean.TRUE.equals(request.merged())) {
                    req.setMsgType(MsgType.MSG_TYPE_MERGED).setContent(MsgContent.newBuilder()
                            .setMerged(MergedContent.newBuilder()
                                    .setTitle("聊天记录")
                                    .addAllMessages(sources)));
                } else {
                    if (sources.size() != 1) {
                        throw new BizException(ErrorCodes.INVALID_PARAM, "单条转发只能选择一条消息");
                    }
                    Msg source = sources.getFirst();
                    req.setMsgType(source.getMsgType()).setContent(source.getContent());
                }
                created.add(handleSend(appId, userId, req.build()).getMsgId());
            } catch (BizException error) {
                throw error;
            } catch (Exception error) {
                throw new BizException(ErrorCodes.SERVER_ERROR, "转发失败");
            }
        }
        return new MessageActionDto(null, created);
    }

    @Override
    @Transactional
    public void onDelivered(String receiverId, long userSeq) {
        Timer.Sample ackStage = metrics == null ? null : metrics.startMessageStage();
        try {
            String msgId = dao.findNewMessageIdByUserSeq(receiverId, userSeq);
            if (msgId == null) {
                if (ackStage != null) {
                    metrics.recordMessageStage(ackStage, "ack", "missing");
                }
                return;
            }
            Msg message = dao.findByMsgId(msgId);
            if (message == null || message.getRestricted() || message.getSenderId().equals(receiverId)) {
                if (ackStage != null) {
                    metrics.recordMessageStage(ackStage, "ack", "ignored");
                }
                return;
            }
            advanceAndNotify(message, MsgStatus.MSG_STATUS_DELIVERED);
            if (ackStage != null) {
                metrics.recordMessageStage(ackStage, "ack", "success");
            }
            log.debug("设备 ACK 处理完成 receiver={} userSeq={} msgId={} clientMsgId={}",
                    receiverId, userSeq, msgId, message.getClientMsgId());
        } catch (RuntimeException error) {
            if (ackStage != null) {
                metrics.recordMessageStage(ackStage, "ack", "failure");
            }
            throw error;
        }
    }

    @Override
    @Transactional
    public void onRead(String readerId, String convId, long readConvSeq) {
        for (Msg message : dao.messagesUpTo(convId, readConvSeq, 500)) {
            if (!message.getSenderId().equals(readerId)
                    && message.getStatusValue() < MsgStatus.MSG_STATUS_READ_VALUE) {
                advanceAndNotify(message, MsgStatus.MSG_STATUS_READ);
            }
        }
    }

    @Override
    @Transactional
    public void deleteLocal(String userId, String msgId) {
        Msg message = dao.findByMsgId(msgId);
        if (message == null || !canAccess(userId, message.getConvId())) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "消息不存在或无权访问");
        }
        dao.deleteLocal(userId, msgId, message.getConvId(), System.currentTimeMillis());
    }

    @Override
    @Transactional
    public void clearHistory(String userId, String convId) {
        if (!canAccess(userId, convId)) {
            throw new BizException(ErrorCodes.CONV_INVALID, "无权访问该会话");
        }
        dao.clearHistory(userId, convId, System.currentTimeMillis());
    }

    private void advanceAndNotify(Msg message, MsgStatus status) {
        long now = System.currentTimeMillis();
        if (!dao.advanceStatus(message.getMsgId(), status.getNumber(), now)) {
            return;
        }
        append(message.getSenderId(), SyncEvent.newBuilder().setEventTime(now)
                .setMsgStatus(MsgStatusEvent.newBuilder().setMsgId(message.getMsgId())
                        .setConvId(message.getConvId()).setStatus(status).setStatusTime(now)));
    }

    private List<String> participants(String convId) {
        List<String> members = dao.listParticipantIds(convId);
        if (!members.isEmpty()) {
            return members;
        }
        return ConvIds.isSingle(convId) ? List.of(ConvIds.participants(convId)) : List.of();
    }

    private boolean canAccess(String userId, String convId) {
        if (ConvIds.isSingle(convId)) {
            return List.of(ConvIds.participants(convId)).contains(userId);
        }
        return dao.isMember(convId, userId);
    }

    private void append(String userId, SyncEvent.Builder event) {
        if (eventWriter != null) {
            eventWriter.append(userId, Ids.newEventId(), event.build());
        }
    }

    private MessageViewDto view(Msg msg) {
        try {
            String contentJson = burned(msg) ? "{}" : JSON.print(msg.getContent());
            return new MessageViewDto(msg.getMsgId(), msg.getClientMsgId(), msg.getConvId(),
                    msg.getConvType().name(), msg.getSenderId(), msg.getMsgType().name(),
                    contentJson, msg.getConvSeq(), msg.getServerTime(),
                    msg.getStatus().name(), msg.getRecalled(), msg.getRestricted(), msg.getIsRequest(),
                    msg.getReplyToMsgId(), msg.getReplySnapshot().getSenderId(),
                    msg.getReplySnapshot().getSenderName(), msg.getReplySnapshot().getDigest(),
                    msg.getBurnAfterReadTtlSeconds());
        } catch (Exception error) {
            throw new IllegalStateException("消息内容 JSON 序列化失败", error);
        }
    }

    private boolean burned(Msg msg) {
        return msg.getBurnAfterReadTtlSeconds() > 0
                && System.currentTimeMillis() > msg.getServerTime() + msg.getBurnAfterReadTtlSeconds() * 1000L;
    }
}
