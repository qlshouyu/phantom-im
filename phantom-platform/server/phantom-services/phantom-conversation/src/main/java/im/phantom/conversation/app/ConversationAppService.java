package im.phantom.conversation.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.conversation.ConversationDraftDto;
import im.phantom.api.conversation.ConversationPageDto;
import im.phantom.api.conversation.ConversationService;
import im.phantom.api.conversation.ConversationSnapshotDto;
import im.phantom.api.conversation.ReadPositionDto;
import im.phantom.api.conversation.UpdateConversationRequest;
import im.phantom.api.message.MessageService;
import im.phantom.api.relation.RelationService;
import im.phantom.common.BizException;
import im.phantom.common.ConvIds;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.common.time.TimeProvider;
import im.phantom.conversation.domain.ConversationRepository;
import im.phantom.conversation.domain.ConversationRepository.ConversationRecord;
import im.phantom.conversation.domain.ConversationRepository.ConversationRow;
import im.phantom.conversation.domain.ConversationRepository.DraftState;
import im.phantom.conversation.domain.ConversationRepository.UserConversationState;
import im.phantom.proto.v1.ConvStateEvent;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.DraftEvent;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.ReadPositionEvent;
import im.phantom.proto.v1.ReadReceiptEvent;
import im.phantom.proto.v1.SyncEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** 会话列表、未读、状态、草稿和只读生命周期用例。 */
@Service
public class ConversationAppService implements ConversationService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int DRAFT_MAX = 5_000;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ConversationRepository repository;
    private final ConversationEventWriter eventWriter;
    private final TimeProvider time;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AppConfigProvider> configProvider;
    private final ObjectProvider<RelationService> relationService;
    private final ObjectProvider<MessageService> messageService;

    public ConversationAppService(ConversationRepository repository, ConversationEventWriter eventWriter,
                                  TimeProvider time, ObjectMapper objectMapper,
                                  ObjectProvider<AppConfigProvider> configProvider,
                                  ObjectProvider<RelationService> relationService,
                                  ObjectProvider<MessageService> messageService) {
        this.repository = repository;
        this.eventWriter = eventWriter;
        this.time = time;
        this.objectMapper = objectMapper;
        this.configProvider = configProvider;
        this.relationService = relationService;
        this.messageService = messageService;
    }

    @Override
    @Transactional
    public void onNewMessage(Msg msg) {
        long now = msg.getServerTime() == 0 ? time.nowMillis() : msg.getServerTime();
        String appId = repository.findAppIdByUserId(msg.getSenderId())
                .orElseThrow(() -> new BizException(ErrorCodes.USER_NOT_FOUND, "发送者不存在"));
        int convType = msg.getConvType() == ConvType.CONV_TYPE_UNSPECIFIED
                ? (ConvIds.isSingle(msg.getConvId()) ? ConvType.CONV_TYPE_SINGLE_VALUE : ConvType.CONV_TYPE_GROUP_VALUE)
                : msg.getConvTypeValue();
        List<String> participants = participants(msg, convType);
        if (!participants.contains(msg.getSenderId())) {
            throw new BizException(ErrorCodes.CONV_INVALID, "发送者不在会话中");
        }
        String targetId = convType == ConvType.CONV_TYPE_GROUP_VALUE
                ? repository.findGroupInfo(msg.getConvId(), msg.getSenderId()).map(ConversationRepository.GroupInfo::groupId).orElse(null)
                : null;
        repository.insertConversationIfAbsent(new ConversationRecord(msg.getConvId(), convType, appId,
                targetId, "ACTIVE", false, now, now));
        repository.advanceConvSeq(msg.getConvId(), msg.getConvSeq());
        for (String participant : participants) {
            repository.ensureMember(msg.getConvId(), participant,
                    participant.equals(msg.getSenderId()) ? "OWNER" : "MEMBER", now);
        }

        String digest = MessageDigestFormatter.format(msg);
        String mentionsJson = mentionsJson(msg.getMentionsList());
        repository.upsertMessageMeta(msg.getMsgId(), msg.getStatusValue(), msg.getRestricted(),
                msg.getReplyToMsgId().isBlank() ? null : msg.getReplyToMsgId(), mentionsJson,
                msg.getBurnAfterReadTtlSeconds(), now);

        for (String participant : participants) {
            repository.ensureUserConversation(participant, msg.getConvId(), now);
            repository.resetDeletedState(participant, msg.getConvId(), now);
            boolean receiver = !participant.equals(msg.getSenderId());
            boolean mentioned = receiver && isMentioned(msg.getMentionsList(), participant);
            boolean incrementUnread = receiver && !msg.getIsRequest()
                    && repository.currentReadPosition(participant, msg.getConvId()) < msg.getConvSeq();
            repository.appendMessageProjection(participant, msg.getConvId(), digest, now,
                    msg.getConvSeq(), incrementUnread, mentioned, now);
            if (receiver && msg.getIsRequest()) {
                repository.markMessageRequest(participant, msg.getConvId(), now);
            }
            if (!receiver) {
                clearDraftAfterSend(participant, msg.getConvId(), now);
            }
        }
    }

    @Override
    @Transactional
    public void createGroupConversation(String appId, String groupId, String convId, String name,
                                        String avatarUrl, List<String> memberIds, String ownerId, long createdAt) {
        if (appId == null || groupId == null || convId == null || memberIds == null || memberIds.isEmpty()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "群会话参数不完整");
        }
        repository.insertConversationIfAbsent(new ConversationRecord(convId, ConvType.CONV_TYPE_GROUP_VALUE,
                appId, groupId, "ACTIVE", false, createdAt, createdAt));
        for (String memberId : memberIds) {
            repository.ensureMember(convId, memberId, memberId.equals(ownerId) ? "OWNER" : "MEMBER", createdAt);
            repository.ensureUserConversation(memberId, convId, createdAt);
        }
    }

    @Override
    @Transactional
    public void addGroupMembers(String convId, List<String> memberIds, long joinedAt) {
        if (convId == null || memberIds == null || memberIds.isEmpty()) {
            return;
        }
        for (String memberId : memberIds) {
            repository.ensureMember(convId, memberId, "MEMBER", joinedAt);
            repository.ensureUserConversation(memberId, convId, joinedAt);
        }
    }

    @Override
    public ConversationPageDto listConversations(String userId, String cursor, int limit) {
        int pageSize = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int offset = decodeCursor(cursor);
        List<ConversationRow> rows = repository.listConversations(userId, offset, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<ConversationRow> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        List<ConversationSnapshotDto> items = pageRows.stream().map(row -> toSnapshot(userId, row)).toList();
        String nextCursor = hasMore ? encodeCursor(offset + pageSize) : null;
        return new ConversationPageDto(items, nextCursor, hasMore, repository.maxUserSeq(userId),
                repository.badgeCount(userId));
    }

    @Override
    public ConversationSnapshotDto getConversation(String userId, String convId) {
        return repository.findConversation(userId, convId)
                .map(row -> toSnapshot(userId, row))
                .orElseThrow(() -> new BizException(ErrorCodes.CONV_INVALID, "会话不存在"));
    }

    @Override
    @Transactional
    public ConversationSnapshotDto updateConversation(String userId, String convId, UpdateConversationRequest request) {
        if (request == null || (request.pinned() == null && request.muted() == null)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "会话状态不能为空");
        }
        UserConversationState state = requireState(userId, convId);
        if (state.deleted()) {
            throw new BizException(ErrorCodes.CONV_INVALID, "会话已删除");
        }
        boolean pinned = request.pinned() == null ? state.pinned() : request.pinned();
        boolean muted = request.muted() == null ? state.muted() : request.muted();
        long now = time.nowMillis();
        long pinnedAt = pinned ? (request.pinned() != null && request.pinned() ? now : state.pinnedAt()) : 0L;
        repository.updateConversationState(userId, convId, pinned, muted, pinnedAt, now);
        if (pinned != state.pinned()) {
            emitConvState(userId, convId, ConvStateEvent.ConvStateField.CONV_STATE_FIELD_PINNED, pinned, now);
        }
        if (muted != state.muted()) {
            emitConvState(userId, convId, ConvStateEvent.ConvStateField.CONV_STATE_FIELD_MUTED, muted, now);
        }
        return getConversation(userId, convId);
    }

    @Override
    @Transactional
    public void deleteConversation(String userId, String convId) {
        UserConversationState state = requireState(userId, convId);
        if (state.deleted()) {
            return;
        }
        long now = time.nowMillis();
        repository.markConversationDeleted(userId, convId, now);
        emitConvState(userId, convId, ConvStateEvent.ConvStateField.CONV_STATE_FIELD_DELETED, true, now);
    }

    @Override
    @Transactional
    public ReadPositionDto markRead(String appId, String userId, String convId, long readConvSeq) {
        requireState(userId, convId);
        long maxConvSeq = repository.maxConvSeq(convId);
        long requested = Math.max(0L, Math.min(readConvSeq, maxConvSeq));
        long current = repository.currentReadPosition(userId, convId);
        long effective = Math.max(current, requested);
        if (effective > current) {
            long now = time.nowMillis();
            repository.upsertReadPosition(userId, convId, effective, now);
            int unread = repository.countUnreadAfter(userId, convId, effective);
            boolean mention = hasMentionAfter(userId, convId, effective);
            repository.updateUnreadProjection(userId, convId, unread, mention, now);
            MessageService messages = messageService.getIfAvailable();
            if (messages != null) {
                messages.onRead(userId, convId, effective);
            }
            append(userId, SyncEvent.newBuilder().setEventTime(now).setReadPosition(
                    ReadPositionEvent.newBuilder().setConvId(convId).setReadConvSeq(effective)), now);
            emitReadReceipt(appId, userId, convId, effective, now);
        }
        return new ReadPositionDto(convId, userId, effective);
    }

    @Override
    public ReadPositionDto getReadPosition(String userId, String convId) {
        requireState(userId, convId);
        return new ReadPositionDto(convId, userId, repository.currentReadPosition(userId, convId));
    }

    @Override
    @Transactional
    public ConversationDraftDto saveDraft(String userId, String convId, String draft, Long editedAt) {
        if (draft != null && draft.codePointCount(0, draft.length()) > DRAFT_MAX) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "草稿最多 5000 字");
        }
        requireState(userId, convId);
        long edit = editedAt == null || editedAt <= 0 ? time.nowMillis() : editedAt;
        long accepted = repository.saveDraftIfNewer(userId, convId, draft == null ? "" : draft, edit, time.nowMillis());
        DraftState actual = repository.findDraft(userId, convId).orElse(new DraftState("", 0));
        if (accepted == edit) {
            emitDraft(userId, convId, actual.draft(), actual.editedAt(), time.nowMillis());
        }
        return new ConversationDraftDto(convId, actual.draft(), actual.editedAt());
    }

    @Override
    @Transactional
    public ConversationDraftDto clearDraft(String userId, String convId, Long editedAt) {
        requireState(userId, convId);
        long edit = editedAt == null || editedAt <= 0 ? time.nowMillis() : editedAt;
        long accepted = repository.saveDraftIfNewer(userId, convId, "", edit, time.nowMillis());
        DraftState actual = repository.findDraft(userId, convId).orElse(new DraftState("", 0));
        if (accepted == edit) {
            emitDraft(userId, convId, "", actual.editedAt(), time.nowMillis());
        }
        return new ConversationDraftDto(convId, actual.draft(), actual.editedAt());
    }

    @Override
    @Transactional
    public void setReadOnly(String userId, String convId, boolean readOnly) {
        UserConversationState state = requireState(userId, convId);
        if (state.readOnly() == readOnly) {
            return;
        }
        long now = time.nowMillis();
        repository.updateReadOnly(userId, convId, readOnly, now);
        emitConvState(userId, convId, ConvStateEvent.ConvStateField.CONV_STATE_FIELD_READ_ONLY, readOnly, now);
    }

    @Override
    @Transactional
    public ConversationSnapshotDto refreshReadOnly(String appId, String userId, String convId) {
        UserConversationState state = requireState(userId, convId);
        ConversationRow row = repository.findConversation(userId, convId)
                .orElseThrow(() -> new BizException(ErrorCodes.CONV_INVALID, "会话不存在"));
        boolean readOnly = computeReadOnly(appId, userId, row);
        if (state.readOnly() != readOnly) {
            long now = time.nowMillis();
            repository.updateReadOnly(userId, convId, readOnly, now);
            emitConvState(userId, convId, ConvStateEvent.ConvStateField.CONV_STATE_FIELD_READ_ONLY, readOnly, now);
        }
        return getConversation(userId, convId);
    }

    @Override
    @Transactional
    public ConversationSnapshotDto handleMessageRequest(String userId, String convId, boolean accept) {
        UserConversationState state = requireState(userId, convId);
        if (!state.isRequest()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "该会话不是消息请求");
        }
        long now = time.nowMillis();
        if (accept) {
            long readSeq = repository.currentReadPosition(userId, convId);
            int unread = repository.countUnreadAfter(userId, convId, readSeq);
            repository.clearMessageRequest(userId, convId, unread, now);
        } else {
            repository.markConversationDeleted(userId, convId, now);
        }
        return accept ? getConversation(userId, convId)
                : new ConversationSnapshotDto(convId, "", "", "", "", "", 0, 0, false,
                false, 0, false, "", 0, 0, 0, true);
    }

    private List<String> participants(Msg msg, int convType) {
        if (convType == ConvType.CONV_TYPE_SINGLE_VALUE) {
            if (msg.getRestricted()) {
                return List.of(msg.getSenderId());
            }
            return List.of(ConvIds.participants(msg.getConvId()));
        }
        return repository.listActiveMemberIds(msg.getConvId());
    }

    private UserConversationState requireState(String userId, String convId) {
        return repository.findUserConversation(userId, convId)
                .orElseThrow(() -> new BizException(ErrorCodes.CONV_INVALID, "会话不存在"));
    }

    private ConversationSnapshotDto toSnapshot(String userId, ConversationRow row) {
        String convType = row.convType() == ConvType.CONV_TYPE_GROUP_VALUE ? "GROUP" : "SINGLE";
        String title = row.convType() == ConvType.CONV_TYPE_GROUP_VALUE
                ? row.groupName() : displayName(userId, row.peerId(), row.peerNickname());
        String avatar = row.convType() == ConvType.CONV_TYPE_GROUP_VALUE
                ? row.groupAvatarUrl() : row.peerAvatarUrl();
        String targetId = row.convType() == ConvType.CONV_TYPE_GROUP_VALUE ? row.groupId() : row.peerId();
        boolean readOnly = computeReadOnly(row.ownerAppId(), userId, row);
        return new ConversationSnapshotDto(row.convId(), convType, targetId,
                title == null ? "" : title, avatar == null ? "" : avatar,
                row.lastMessageDigest(), row.lastMessageAt(), row.unreadCount(), row.mentionMe(),
                row.pinned(), row.pinnedAt(), row.muted(), row.draft(), row.draftEditedAt(),
                row.maxConvSeq(), row.readConvSeq(), readOnly);
    }

    private String displayName(String userId, String peerId, String fallback) {
        RelationService relations = relationService.getIfAvailable();
        if (relations == null || peerId == null) {
            return fallback;
        }
        return relations.displayName(userId, peerId, fallback);
    }

    private boolean computeReadOnly(String appId, String userId, ConversationRow row) {
        if (row.conversationReadOnly() || row.userReadOnly()) {
            return true;
        }
        if (row.convType() == ConvType.CONV_TYPE_GROUP_VALUE) {
            return !"ACTIVE".equals(row.groupStatus()) || !"ACTIVE".equals(row.groupMemberStatus());
        }
        RelationService relations = relationService.getIfAvailable();
        if (relations == null || row.peerId() == null) {
            return false;
        }
        try {
            relations.authorizeDirectMessage(appId, userId, row.peerId());
            return false;
        } catch (BizException denied) {
            return true;
        }
    }

    private void clearDraftAfterSend(String userId, String convId, long now) {
        DraftState draft = repository.findDraft(userId, convId).orElse(new DraftState("", 0));
        if (draft.draft() == null || draft.draft().isBlank()) {
            return;
        }
        repository.clearDraft(userId, convId, now);
        emitDraft(userId, convId, "", now, now);
    }

    private boolean hasMentionAfter(String userId, String convId, long readConvSeq) {
        for (String json : repository.listMentionJsonAfter(convId, readConvSeq)) {
            if (isMentioned(parseMentions(json), userId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMentioned(List<String> mentions, String userId) {
        return mentions.stream().anyMatch(value -> value.equals(userId) || "ALL".equalsIgnoreCase(value));
    }

    private List<String> parseMentions(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, STRING_LIST);
        } catch (Exception malformed) {
            return List.of();
        }
    }

    private String mentionsJson(List<String> mentions) {
        try {
            return objectMapper.writeValueAsString(mentions == null ? List.of() : mentions);
        } catch (Exception impossible) {
            return "[]";
        }
    }

    private void emitConvState(String userId, String convId, ConvStateEvent.ConvStateField field,
                               boolean value, long now) {
        append(userId, SyncEvent.newBuilder().setEventTime(now).setConvState(ConvStateEvent.newBuilder()
                .setConvId(convId).setField(field).setBoolValue(value).setOpTime(now)), now);
    }

    private void emitDraft(String userId, String convId, String draft, long editedAt, long now) {
        append(userId, SyncEvent.newBuilder().setEventTime(now).setDraft(DraftEvent.newBuilder()
                .setConvId(convId).setDraft(draft).setEditedAt(editedAt)), now);
    }

    private void emitReadReceipt(String appId, String userId, String convId, long readConvSeq, long now) {
        if (!readReceiptEnabled(appId)) {
            return;
        }
        ConversationRow row = repository.findConversation(userId, convId).orElse(null);
        if (row == null) {
            return;
        }
        if (row.convType() == ConvType.CONV_TYPE_GROUP_VALUE) {
            int readCount = repository.countReadersAtLeast(convId, readConvSeq);
            for (String memberId : repository.listActiveMemberIds(convId)) {
                if (!memberId.equals(userId)) {
                    append(memberId, SyncEvent.newBuilder().setEventTime(now).setReadReceipt(
                            ReadReceiptEvent.newBuilder().setConvId(convId)
                                    .setReadConvSeq(readConvSeq).setReaderId(userId)
                                    .setReadCount(readCount)), now);
                }
            }
            return;
        }
        repository.findPeerId(convId, userId).ifPresent(peer -> append(peer,
                SyncEvent.newBuilder().setEventTime(now).setReadReceipt(ReadReceiptEvent.newBuilder()
                        .setConvId(convId).setReadConvSeq(readConvSeq).setReaderId(userId)), now));
    }

    private boolean readReceiptEnabled(String appId) {
        AppConfigProvider provider = configProvider.getIfAvailable();
        if (provider == null || appId == null) {
            return true;
        }
        try {
            return provider.getConfig(appId).readReceiptEnabled();
        } catch (BizException missing) {
            return true;
        }
    }

    private void append(String userId, SyncEvent.Builder event, long now) {
        event.setEventTime(now);
        eventWriter.append(userId, Ids.newEventId(), event.build());
    }

    private String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return Math.max(0, Integer.parseInt(decoded));
        } catch (RuntimeException invalid) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "分页游标非法");
        }
    }
}
