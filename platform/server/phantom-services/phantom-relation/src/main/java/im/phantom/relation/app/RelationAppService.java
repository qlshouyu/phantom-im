package im.phantom.relation.app;

import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.conversation.ConversationService;
import im.phantom.api.relation.BlacklistEntryDto;
import im.phantom.api.relation.CreateFriendRequestRequest;
import im.phantom.api.relation.FriendGroupDto;
import im.phantom.api.relation.FriendRequestAction;
import im.phantom.api.relation.FriendRequestDto;
import im.phantom.api.relation.FriendRequestPageDto;
import im.phantom.api.relation.FriendRequestResultDto;
import im.phantom.api.relation.FriendSummaryDto;
import im.phantom.api.relation.DirectMessagePolicy;
import im.phantom.api.relation.QrCardDto;
import im.phantom.api.relation.QrCardScanResultDto;
import im.phantom.api.relation.RelationService;
import im.phantom.api.relation.RelationSettingsDto;
import im.phantom.api.relation.RelationUserDto;
import im.phantom.api.relation.UpdateFriendRequest;
import im.phantom.api.relation.UpdateRelationSettingsRequest;
import im.phantom.api.relation.UserCardDto;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.common.ConvIds;
import im.phantom.common.time.TimeProvider;
import im.phantom.proto.v1.ProfileEvent;
import im.phantom.proto.v1.RelationEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.UserLite;
import im.phantom.relation.domain.RelationRepository;
import im.phantom.relation.domain.RelationRepository.BlacklistRecord;
import im.phantom.relation.domain.RelationRepository.FriendGroupRecord;
import im.phantom.relation.domain.RelationRepository.FriendRecord;
import im.phantom.relation.domain.RelationRepository.FriendRequestRecord;
import im.phantom.relation.domain.RelationRepository.QrCardRecord;
import im.phantom.relation.domain.RelationRepository.RelationSettingRecord;
import im.phantom.relation.domain.RelationRepository.UserRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 关系链托管用例：用户发现、好友申请、好友设置、黑名单和二维码名片。 */
@Service
public class RelationAppService implements RelationService {

    private static final int VERIFICATION_MESSAGE_MAX = 30;
    private static final int REMARK_MAX = 64;
    private static final int GROUP_NAME_MAX = 64;
    private static final int DEFAULT_FRIEND_LIMIT = 10_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RelationRepository repository;
    private final RelationEventWriter eventWriter;
    private final TimeProvider time;
    private final ObjectProvider<AppConfigProvider> configProvider;
    private final ObjectProvider<ConversationService> conversationService;

    public RelationAppService(RelationRepository repository, RelationEventWriter eventWriter,
                              TimeProvider time, ObjectProvider<AppConfigProvider> configProvider,
                              ObjectProvider<ConversationService> conversationService) {
        this.repository = repository;
        this.eventWriter = eventWriter;
        this.time = time;
        this.configProvider = configProvider;
        this.conversationService = conversationService;
    }

    @Override
    public UserCardDto getUserCard(String appId, String viewerId, String targetId) {
        requireActiveUser(appId, viewerId);
        UserRecord target = requireActiveUser(appId, targetId);
        if (repository.isBlacklisted(targetId, viewerId)) {
            throw new BizException(ErrorCodes.USER_NOT_FOUND, "用户不存在");
        }
        String remark = repository.findFriendSetting(viewerId, targetId)
                .map(RelationRepository.FriendSettingRecord::remark)
                .orElse("");
        return new UserCardDto(targetId, target.nickname(), target.avatarUrl(), remark,
                repository.isFriend(viewerId, targetId), repository.isBlacklisted(viewerId, targetId));
    }

    @Override
    @Transactional
    public FriendRequestResultDto createFriendRequest(String appId, String fromUserId,
                                                       CreateFriendRequestRequest request) {
        UserRecord from = requireActiveUser(appId, fromUserId);
        validateCreateRequest(request);
        String toUserId = request.toUserId();
        if (fromUserId.equals(toUserId)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "不能添加自己为好友");
        }
        UserRecord to = requireActiveUser(appId, toUserId);
        if (repository.isBlacklisted(fromUserId, toUserId)) {
            throw new BizException(ErrorCodes.IN_MY_BLACKLIST, "请先解除拉黑");
        }
        if (repository.isBlacklisted(toUserId, fromUserId)) {
            throw new BizException(ErrorCodes.REJECTED_BY_PEER, "对方拒绝接收申请");
        }
        if (repository.isFriend(fromUserId, toUserId) && repository.isFriend(toUserId, fromUserId)) {
            throw new BizException(ErrorCodes.DUPLICATE_APPLY, "你们已经是好友");
        }
        if (repository.findPendingFriendRequest(appId, fromUserId, toUserId).isPresent()) {
            throw new BizException(ErrorCodes.DUPLICATE_APPLY, "好友申请已发送，请等待对方处理");
        }
        checkFriendLimit(toUserId);

        long now = time.nowMillis();
        FriendRequestRecord pending = new FriendRequestRecord(
                Ids.newFriendRequestId(), appId, fromUserId, toUserId,
                request.verificationMessage() == null ? "" : request.verificationMessage(),
                "PENDING", now, null, null, fromUserId + ":" + toUserId);
        try {
            repository.insertFriendRequest(pending);
        } catch (DuplicateKeyException duplicate) {
            throw new BizException(ErrorCodes.DUPLICATE_APPLY, "好友申请已发送，请等待对方处理");
        }

        if (relationSettings(toUserId).autoAcceptRequests()) {
            FriendRequestRecord accepted = acceptPending(pending, now);
            return new FriendRequestResultDto(accepted.requestId(), accepted.status(), true);
        }
        notifyRelation(toUserId, RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_RECEIVED,
                from, pending, now);
        notifyRelation(fromUserId, RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_SENT,
                to, pending, now);
        return new FriendRequestResultDto(pending.requestId(), pending.status(), false);
    }

    @Override
    @Transactional
    public FriendRequestPageDto listFriendRequests(String userId, boolean includeHandled) {
        int unreadCount = repository.countUnreadRequests(userId);
        List<FriendRequestDto> items = repository.listFriendRequests(userId, includeHandled).stream()
                .map(request -> toRequestDto(request, userId)).toList();
        repository.markIncomingRequestsRead(userId, time.nowMillis());
        return new FriendRequestPageDto(items, unreadCount);
    }

    @Override
    @Transactional
    public FriendRequestDto handleFriendRequest(String appId, String userId, String requestId,
                                                 FriendRequestAction action) {
        if (action == null) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "处理动作不能为空");
        }
        FriendRequestRecord request = repository.findFriendRequest(appId, requestId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "好友申请不存在"));
        if (!userId.equals(request.toUserId())) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "无权处理该好友申请");
        }
        if (!"PENDING".equals(request.status())) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "该申请已被处理");
        }
        long now = time.nowMillis();
        FriendRequestRecord handled = switch (action) {
            case ACCEPT -> acceptPending(request, now);
            case REJECT -> rejectPending(request, now);
            case IGNORE -> ignorePending(request, now);
        };
        return toRequestDto(handled, userId);
    }

    @Override
    public List<FriendSummaryDto> listFriends(String userId) {
        List<FriendSummaryDto> friends = new ArrayList<>();
        for (FriendRecord record : repository.listFriends(userId)) {
            friends.add(toFriendSummary(record));
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        friends.sort(Comparator.comparing(FriendSummaryDto::pinned).reversed()
                .thenComparing(FriendSummaryDto::displayName, collator)
                .thenComparing(FriendSummaryDto::userId));
        return List.copyOf(friends);
    }

    @Override
    @Transactional
    public FriendSummaryDto updateFriend(String userId, String friendId, UpdateFriendRequest request) {
        if (request == null) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "好友设置不能为空");
        }
        if (!repository.isFriend(userId, friendId)) {
            throw new BizException(ErrorCodes.NOT_FRIEND, "对方不是你的好友");
        }
        RelationRepository.FriendSettingRecord current = repository.findFriendSetting(userId, friendId)
                .orElse(new RelationRepository.FriendSettingRecord("", false));
        String remark = request.remark() == null ? current.remark() : request.remark();
        validateRemark(remark);
        boolean pinned = request.pinned() == null ? current.pinned() : request.pinned();
        List<String> currentGroups = currentFriendGroups(userId, friendId);
        List<String> groups = request.groupIds() == null ? currentGroups : validateGroups(userId, request.groupIds());
        long now = time.nowMillis();
        repository.upsertFriendSetting(userId, friendId, remark, pinned, now);
        if (request.groupIds() != null) {
            repository.replaceFriendGroups(userId, friendId, groups);
        }

        UserRecord friend = requireUserByUserId(friendId);
        if (!remark.equals(current.remark())) {
            append(userId, SyncEvent.newBuilder().setEventTime(now).setProfile(ProfileEvent.newBuilder()
                    .setType(ProfileEvent.ProfileEventType.PROFILE_EVENT_TYPE_FRIEND_REMARK)
                    .setTargetId(friendId).putChanged("remark", remark)), now);
        }
        if (pinned != current.pinned() || !groups.equals(currentGroups)) {
            notifyRelation(userId, RelationEvent.RelationEventType.RELATION_EVENT_TYPE_FRIEND_SETTING_CHANGED,
                    friend, null, now, groups, pinned, remark);
        }
        return findFriendSummary(userId, friendId);
    }

    @Override
    @Transactional
    public void deleteFriend(String userId, String friendId) {
        if (!repository.isFriend(userId, friendId)) {
            return;
        }
        Optional<UserRecord> peer = findUserByUserId(friendId);
        repository.deleteFriendData(userId, friendId);
        peer.ifPresent(value -> notifyRelation(userId,
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_FRIEND_DELETED,
                value, null, time.nowMillis()));
        findUserByUserId(userId).ifPresent(owner -> refreshConversationReadOnly(owner.appId(), userId, friendId));
    }

    @Override
    public List<FriendGroupDto> listFriendGroups(String userId) {
        return repository.listFriendGroups(userId).stream().map(this::toGroupDto).toList();
    }

    @Override
    @Transactional
    public FriendGroupDto createFriendGroup(String userId, String name) {
        validateGroupName(name);
        long now = time.nowMillis();
        FriendGroupRecord group = new FriendGroupRecord(Ids.newFriendGroupId(), name, now, 0);
        try {
            repository.insertFriendGroup(userId, group);
        } catch (DuplicateKeyException duplicate) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "分组名称已存在");
        }
        notifyGroup(userId, group.groupId(), group.name(), false, now);
        return toGroupDto(group);
    }

    @Override
    @Transactional
    public FriendGroupDto renameFriendGroup(String userId, String groupId, String name) {
        validateGroupName(name);
        FriendGroupRecord current = repository.findFriendGroup(groupId, userId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "分组不存在"));
        try {
            if (!repository.renameFriendGroup(groupId, userId, name)) {
                throw new BizException(ErrorCodes.INVALID_PARAM, "分组不存在");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "分组名称已存在");
        }
        long now = time.nowMillis();
        notifyGroup(userId, groupId, name, false, now);
        return new FriendGroupDto(groupId, name, current.memberCount());
    }

    @Override
    @Transactional
    public void deleteFriendGroup(String userId, String groupId) {
        FriendGroupRecord current = repository.findFriendGroup(groupId, userId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "分组不存在"));
        if (!repository.deleteFriendGroup(groupId, userId)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "分组不存在");
        }
        notifyGroup(userId, groupId, current.name(), true, time.nowMillis());
    }

    @Override
    public RelationSettingsDto getRelationSettings(String userId) {
        RelationSettingRecord setting = relationSettings(userId);
        return new RelationSettingsDto(setting.autoAcceptRequests(), setting.friendLimit());
    }

    @Override
    @Transactional
    public RelationSettingsDto updateRelationSettings(String userId, UpdateRelationSettingsRequest request) {
        RelationSettingRecord current = relationSettings(userId);
        boolean autoAccept = request == null || request.autoAcceptRequests() == null
                ? current.autoAcceptRequests() : request.autoAcceptRequests();
        if (autoAccept != current.autoAcceptRequests()) {
            long now = time.nowMillis();
            repository.upsertAutoAccept(userId, autoAccept, now);
            append(userId, SyncEvent.newBuilder().setEventTime(now).setProfile(ProfileEvent.newBuilder()
                    .setType(ProfileEvent.ProfileEventType.PROFILE_EVENT_TYPE_MY_SETTINGS)
                    .putChanged("auto_accept_requests", Boolean.toString(autoAccept))), now);
        }
        return new RelationSettingsDto(autoAccept, current.friendLimit());
    }

    @Override
    @Transactional
    public void blockUser(String appId, String userId, String blockedId) {
        requireActiveUser(appId, userId);
        UserRecord blocked = requireActiveUser(appId, blockedId);
        if (userId.equals(blockedId)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "不能拉黑自己");
        }
        if (repository.isBlacklisted(userId, blockedId)) {
            return;
        }
        repository.deleteFriendData(userId, blockedId);
        repository.deleteFriendData(blockedId, userId);
        repository.insertBlacklist(userId, blockedId, time.nowMillis());
        notifyRelation(userId, RelationEvent.RelationEventType.RELATION_EVENT_TYPE_BLACKLIST_ADDED,
                blocked, null, time.nowMillis());
        refreshConversationReadOnly(appId, userId, blockedId);
        refreshConversationReadOnly(appId, blockedId, userId);
    }

    @Override
    @Transactional
    public void unblockUser(String userId, String blockedId) {
        if (repository.deleteBlacklist(userId, blockedId)) {
            findUserByUserId(blockedId).ifPresent(blocked -> notifyRelation(userId,
                    RelationEvent.RelationEventType.RELATION_EVENT_TYPE_BLACKLIST_REMOVED,
                    blocked, null, time.nowMillis()));
            findUserByUserId(userId).ifPresent(owner -> refreshConversationReadOnly(owner.appId(), userId, blockedId));
            findUserByUserId(blockedId).ifPresent(blocked -> refreshConversationReadOnly(
                    blocked.appId(), blockedId, userId));
        }
    }

    @Override
    public List<BlacklistEntryDto> listBlacklist(String userId) {
        return repository.listBlacklist(userId).stream().map(this::toBlacklistDto).toList();
    }

    @Override
    @Transactional
    public QrCardDto getOrCreateQrCard(String userId) {
        long now = time.nowMillis();
        return repository.findActiveQrCardByUser(userId, now).map(this::toQrCardDto)
                .orElseGet(() -> createQrCard(userId, now));
    }

    @Override
    @Transactional
    public QrCardDto refreshQrCard(String userId) {
        long now = time.nowMillis();
        repository.revokeActiveQrCards(userId);
        return createQrCard(userId, now);
    }

    @Override
    @Transactional
    public QrCardScanResultDto scanQrCard(String appId, String viewerId, String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "二维码内容不能为空");
        }
        QrCardRecord card = repository.findActiveQrCardByHash(tokenHash(token), time.nowMillis())
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "二维码已失效"));
        UserCardDto user = getUserCard(appId, viewerId, card.userId());
        return new QrCardScanResultDto(user);
    }

    @Override
    public String displayName(String ownerId, String targetId, String fallbackNickname) {
        return repository.findFriendSetting(ownerId, targetId)
                .map(RelationRepository.FriendSettingRecord::remark)
                .filter(remark -> remark != null && !remark.isBlank())
                .orElse(fallbackNickname);
    }

    @Override
    public void authorizeDirectMessage(String appId, String senderId, String receiverId) {
        if (senderId == null || receiverId == null || senderId.equals(receiverId)) {
            throw new BizException(ErrorCodes.CONV_INVALID, "单聊成员非法");
        }
        if (repository.isBlacklisted(senderId, receiverId)) {
            throw new BizException(ErrorCodes.IN_MY_BLACKLIST, "你已将对方拉黑");
        }
        if (repository.isBlacklisted(receiverId, senderId)) {
            throw new BizException(ErrorCodes.REJECTED_BY_PEER, "对方拒绝接收消息");
        }
        if (relationHosted(appId) && !repository.isFriend(senderId, receiverId)) {
            throw new BizException(ErrorCodes.NOT_FRIEND, "对方不是你的好友");
        }
    }

    @Override
    public DirectMessagePolicy evaluateDirectMessage(String appId, String senderId, String receiverId) {
        return evaluateDirectMessage(appId, senderId, receiverId, relationHosted(appId));
    }

    @Override
    public DirectMessagePolicy evaluateDirectMessage(String appId, String senderId, String receiverId,
                                                      boolean relationHosted) {
        if (senderId == null || receiverId == null || senderId.equals(receiverId)) {
            throw new BizException(ErrorCodes.CONV_INVALID, "单聊成员非法");
        }
        RelationRepository.DirectMessageRelationState state =
                repository.directMessageRelationState(senderId, receiverId);
        if (state.senderBlacklistedReceiver()) {
            throw new BizException(ErrorCodes.IN_MY_BLACKLIST, "你已将对方拉黑");
        }
        if (state.receiverBlacklistedSender()) {
            return DirectMessagePolicy.RESTRICTED;
        }
        if (!relationHosted) {
            return DirectMessagePolicy.ALLOWED;
        }
        boolean senderStillHasPeer = state.senderStillHasPeer();
        boolean peerStillHasSender = state.peerStillHasSender();
        if (!senderStillHasPeer) {
            // 两个人互删或从未成为好友时进入消息请求；只有发送方单方面删除时拒绝。
            return peerStillHasSender ? throwNotFriend() : DirectMessagePolicy.MESSAGE_REQUEST;
        }
        return peerStillHasSender ? DirectMessagePolicy.ALLOWED : DirectMessagePolicy.RESTRICTED;
    }

    private DirectMessagePolicy throwNotFriend() {
        throw new BizException(ErrorCodes.NOT_FRIEND, "对方不是你的好友");
    }

    private FriendRequestRecord acceptPending(FriendRequestRecord request, long now) {
        checkFriendLimit(request.fromUserId());
        checkFriendLimit(request.toUserId());
        if (!repository.transitionRequest(request.requestId(), "PENDING", "ACCEPTED", now)) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "该申请已被处理");
        }
        connectFriends(request.fromUserId(), request.toUserId(), now);
        connectFriends(request.toUserId(), request.fromUserId(), now);
        UserRecord from = requireUserByUserId(request.fromUserId());
        UserRecord to = requireUserByUserId(request.toUserId());
        notifyRelation(request.fromUserId(),
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_ACCEPTED, to, request, now);
        notifyRelation(request.toUserId(),
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_ACCEPTED, from, request, now);
        refreshConversationReadOnly(request.appId(), request.fromUserId(), request.toUserId());
        refreshConversationReadOnly(request.appId(), request.toUserId(), request.fromUserId());
        return new FriendRequestRecord(request.requestId(), request.appId(), request.fromUserId(),
                request.toUserId(), request.verificationMessage(), "ACCEPTED", request.createdAt(), now,
                now, null);
    }

    private FriendRequestRecord rejectPending(FriendRequestRecord request, long now) {
        if (!repository.transitionRequest(request.requestId(), "PENDING", "REJECTED", now)) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "该申请已被处理");
        }
        UserRecord from = requireUserByUserId(request.fromUserId());
        UserRecord to = requireUserByUserId(request.toUserId());
        notifyRelation(request.fromUserId(),
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_REJECTED, to, request, now);
        notifyRelation(request.toUserId(),
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_REJECTED, from, request, now);
        return new FriendRequestRecord(request.requestId(), request.appId(), request.fromUserId(),
                request.toUserId(), request.verificationMessage(), "REJECTED", request.createdAt(), now,
                now, null);
    }

    private FriendRequestRecord ignorePending(FriendRequestRecord request, long now) {
        if (!repository.transitionRequest(request.requestId(), "PENDING", "IGNORED", now)) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "该申请已被处理");
        }
        UserRecord from = requireUserByUserId(request.fromUserId());
        notifyRelation(request.toUserId(), RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_IGNORED,
                from, request, now);
        return new FriendRequestRecord(request.requestId(), request.appId(), request.fromUserId(),
                request.toUserId(), request.verificationMessage(), "IGNORED", request.createdAt(), now,
                now, null);
    }

    private void connectFriends(String userId, String friendId, long now) {
        if (!repository.isFriend(userId, friendId)) {
            repository.insertFriend(userId, friendId, now);
        }
    }

    private void checkFriendLimit(String userId) {
        int limit = relationSettings(userId).friendLimit();
        if (repository.countFriends(userId) >= limit) {
            throw new BizException(ErrorCodes.FRIEND_LIMIT_EXCEEDED, "好友数量已达上限");
        }
    }

    private RelationSettingRecord relationSettings(String userId) {
        return repository.findRelationSetting(userId)
                .orElse(new RelationSettingRecord(false, DEFAULT_FRIEND_LIMIT));
    }

    private boolean relationHosted(String appId) {
        if (appId == null) {
            return true;
        }
        AppConfigProvider provider = configProvider.getIfAvailable();
        if (provider == null) {
            return true;
        }
        try {
            return provider.getConfig(appId).relationHosted();
        } catch (BizException missingConfig) {
            return true;
        }
    }

    private void refreshConversationReadOnly(String appId, String userId, String peerId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshConversationReadOnlyNow(appId, userId, peerId);
                }
            });
            return;
        }
        refreshConversationReadOnlyNow(appId, userId, peerId);
    }

    private void refreshConversationReadOnlyNow(String appId, String userId, String peerId) {
        ConversationService conversations = conversationService.getIfAvailable();
        if (conversations == null) {
            return;
        }
        try {
            conversations.refreshReadOnly(appId, userId, ConvIds.single(userId, peerId));
        } catch (BizException ignored) {
            // 会话尚未生成时无需刷新只读状态。
        }
    }

    private FriendRequestDto toRequestDto(FriendRequestRecord request, String viewerId) {
        boolean incoming = viewerId.equals(request.toUserId());
        String peerId = incoming ? request.fromUserId() : request.toUserId();
        UserRecord peer = requireUserByUserId(peerId);
        return new FriendRequestDto(request.requestId(), request.fromUserId(), request.toUserId(),
                new RelationUserDto(peer.userId(), peer.nickname(), peer.avatarUrl()),
                request.verificationMessage(), request.status(), incoming ? "INCOMING" : "OUTGOING",
                request.createdAt(), request.handledAt(),
                incoming && "PENDING".equals(request.status()) && request.readAt() == null);
    }

    private FriendSummaryDto findFriendSummary(String userId, String friendId) {
        return repository.listFriends(userId).stream()
                .filter(friend -> friend.friendId().equals(friendId))
                .findFirst().map(this::toFriendSummary)
                .orElseThrow(() -> new BizException(ErrorCodes.NOT_FRIEND, "对方不是你的好友"));
    }

    private FriendSummaryDto toFriendSummary(FriendRecord friend) {
        String displayName = friend.remark() == null || friend.remark().isBlank()
                ? friend.nickname() : friend.remark();
        return new FriendSummaryDto(friend.friendId(), friend.nickname(), friend.avatarUrl(), displayName,
                friend.remark(), friend.pinned(), friend.groupIds());
    }

    private FriendGroupDto toGroupDto(FriendGroupRecord group) {
        return new FriendGroupDto(group.groupId(), group.name(), group.memberCount());
    }

    private BlacklistEntryDto toBlacklistDto(BlacklistRecord record) {
        return new BlacklistEntryDto(record.blockedId(), record.nickname(), record.avatarUrl(), record.createdAt());
    }

    private QrCardDto toQrCardDto(QrCardRecord card) {
        return new QrCardDto(card.cardId(), card.tokenValue(), cardUrl(card.tokenValue()), card.expiresAt());
    }

    private QrCardDto createQrCard(String userId, long now) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        QrCardRecord card = new QrCardRecord(Ids.newQrCardId(), userId, tokenHash(token), token,
                "ACTIVE", now, null);
        repository.insertQrCard(card);
        return toQrCardDto(card);
    }

    private String cardUrl(String token) {
        return "phantom://friend-card/" + token;
    }

    private String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private List<String> currentFriendGroups(String userId, String friendId) {
        return repository.listFriends(userId).stream()
                .filter(friend -> friend.friendId().equals(friendId))
                .findFirst().map(FriendRecord::groupIds).orElse(List.of());
    }

    private List<String> validateGroups(String userId, List<String> groupIds) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String groupId : groupIds) {
            if (groupId == null || groupId.isBlank() || !repository.isGroupOwnedBy(groupId, userId)) {
                throw new BizException(ErrorCodes.INVALID_PARAM, "好友分组不存在");
            }
            unique.add(groupId);
        }
        return List.copyOf(unique);
    }

    private void notifyRelation(String userId, RelationEvent.RelationEventType type, UserRecord peer,
                                FriendRequestRecord request, long now) {
        List<String> groups = List.of();
        boolean pinned = false;
        String remark = "";
        notifyRelation(userId, type, peer, request, now, groups, pinned, remark);
    }

    private void notifyRelation(String userId, RelationEvent.RelationEventType type, UserRecord peer,
                                FriendRequestRecord request, long now, List<String> groups,
                                boolean pinned, String remark) {
        RelationEvent.Builder event = RelationEvent.newBuilder()
                .setType(type)
                .setPeerId(peer.userId())
                .setPeer(toUserLite(peer))
                .setCreatedAt(now)
                .addAllGroupIds(groups)
                .setPinned(pinned)
                .setRemark(remark == null ? "" : remark);
        if (request != null) {
            event.setRequestId(request.requestId())
                    .setVerificationMessage(request.verificationMessage())
                    .setCreatedAt(request.createdAt());
        }
        append(userId, SyncEvent.newBuilder().setEventTime(now).setRelation(event), now);
    }

    private void notifyGroup(String userId, String groupId, String groupName, boolean deleted, long now) {
        append(userId, SyncEvent.newBuilder().setEventTime(now).setRelation(RelationEvent.newBuilder()
                .setType(RelationEvent.RelationEventType.RELATION_EVENT_TYPE_GROUP_CHANGED)
                .setGroupId(groupId).setGroupName(groupName).setGroupDeleted(deleted).setCreatedAt(now)), now);
    }

    private void append(String userId, SyncEvent.Builder event, long now) {
        event.setEventTime(now);
        eventWriter.append(userId, Ids.newEventId(), event.build());
    }

    private UserLite toUserLite(UserRecord user) {
        return UserLite.newBuilder().setUserId(user.userId()).setNickname(user.nickname())
                .setAvatarUrl(user.avatarUrl()).build();
    }

    private UserRecord requireActiveUser(String appId, String userId) {
        if (appId == null || userId == null || userId.isBlank()) {
            throw new BizException(ErrorCodes.USER_NOT_FOUND, "用户不存在");
        }
        UserRecord user = repository.findUser(appId, userId)
                .orElseThrow(() -> new BizException(ErrorCodes.USER_NOT_FOUND, "用户不存在"));
        if (!"ACTIVE".equals(user.status())) {
            throw new BizException(ErrorCodes.USER_NOT_FOUND, "用户不可用");
        }
        return user;
    }

    private UserRecord requireUserByUserId(String userId) {
        return findUserByUserId(userId)
                .orElseThrow(() -> new BizException(ErrorCodes.USER_NOT_FOUND, "用户不存在"));
    }

    private Optional<UserRecord> findUserByUserId(String userId) {
        return repository.findUserById(userId);
    }

    private void validateCreateRequest(CreateFriendRequestRequest request) {
        if (request == null || request.toUserId() == null || request.toUserId().isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "目标用户不能为空");
        }
        String message = request.verificationMessage();
        if (message != null && message.codePointCount(0, message.length()) > VERIFICATION_MESSAGE_MAX) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "验证消息最多 30 字");
        }
    }

    private void validateRemark(String remark) {
        if (remark != null && remark.codePointCount(0, remark.length()) > REMARK_MAX) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "好友备注最多 64 字");
        }
    }

    private void validateGroupName(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > GROUP_NAME_MAX) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "分组名称必须为 1-64 字");
        }
    }
}
