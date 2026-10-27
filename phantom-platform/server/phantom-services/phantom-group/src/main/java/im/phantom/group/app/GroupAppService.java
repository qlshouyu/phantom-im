package im.phantom.group.app;

import im.phantom.api.conversation.ConversationService;
import im.phantom.api.group.GroupDtos.AnnouncementDto;
import im.phantom.api.group.GroupDtos.AnnouncementRequest;
import im.phantom.api.group.GroupDtos.CreateGroupRequest;
import im.phantom.api.group.GroupDtos.CreateInviteRequest;
import im.phantom.api.group.GroupDtos.GroupDetailDto;
import im.phantom.api.group.GroupDtos.InviteDto;
import im.phantom.api.group.GroupDtos.InviteMembersRequest;
import im.phantom.api.group.GroupDtos.MemberActionRequest;
import im.phantom.api.group.GroupDtos.MuteRequest;
import im.phantom.api.group.GroupDtos.TransferOwnerRequest;
import im.phantom.api.group.GroupDtos.UpdateGroupRequest;
import im.phantom.api.group.GroupDtos.UpdateRoleRequest;
import im.phantom.api.group.GroupDtos.JoinByInviteRequest;
import im.phantom.api.group.GroupDtos.JoinResultDto;
import im.phantom.api.group.GroupDtos.HandleJoinRequest;
import im.phantom.api.group.GroupMemberDto;
import im.phantom.api.group.GroupService;
import im.phantom.api.group.GroupSummaryDto;
import im.phantom.api.relation.RelationService;
import im.phantom.common.BizException;
import im.phantom.common.ConvIds;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.common.time.TimeProvider;
import im.phantom.group.infra.JdbcGroupRepository;
import im.phantom.group.infra.JdbcGroupRepository.AnnouncementRecord;
import im.phantom.group.infra.JdbcGroupRepository.GroupRecord;
import im.phantom.group.infra.JdbcGroupRepository.InviteRecord;
import im.phantom.group.infra.JdbcGroupRepository.MemberRecord;
import im.phantom.proto.v1.GroupEvent;
import im.phantom.proto.v1.SyncEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 群聊权限、成员、角色、禁言、公告、邀请和生命周期用例。 */
@Service
public class GroupAppService implements GroupService {

    private static final int MAX_MEMBERS = 500;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcGroupRepository repository;
    private final ConversationService conversations;
    private final RelationService relations;
    private final GroupEventWriter events;
    private final TimeProvider time;
    private final Map<String, Long> mentionAllLastAt = new ConcurrentHashMap<>();

    public GroupAppService(JdbcGroupRepository repository, ConversationService conversations,
                           RelationService relations, GroupEventWriter events, TimeProvider time) {
        this.repository = repository;
        this.conversations = conversations;
        this.relations = relations;
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional
    public GroupSummaryDto createGroup(String appId, String ownerId, CreateGroupRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.memberIds() == null) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "群名称和成员不能为空");
        }
        String name = validateName(request.name());
        List<String> invitees = distinct(request.memberIds());
        invitees.remove(ownerId);
        if (invitees.size() < 2) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "建群至少需要选择 2 名好友");
        }
        if (invitees.size() + 1 > MAX_MEMBERS) {
            throw new BizException(ErrorCodes.GROUP_FULL, "群成员不能超过 500 人");
        }
        Set<String> friends = relations.listFriends(ownerId).stream()
                .map(friend -> friend.userId()).collect(java.util.stream.Collectors.toSet());
        if (!friends.containsAll(invitees)) {
            throw new BizException(ErrorCodes.NOT_FRIEND, "只能邀请好友入群");
        }
        long now = time.nowMillis();
        String groupId = Ids.newGroupId();
        String convId = ConvIds.group(groupId);
        repository.insertGroup(new GroupRecord(groupId, convId, name, "", ownerId, "ACTIVE",
                true, false, 0, now, now));
        repository.insertMember(groupId, ownerId, "OWNER", now);
        for (String member : invitees) {
            repository.insertMember(groupId, member, "MEMBER", now);
        }
        List<String> members = new ArrayList<>();
        members.add(ownerId);
        members.addAll(invitees);
        conversations.createGroupConversation(appId, groupId, convId, name, "", members, ownerId, now);
        notifyGroup(members, GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MEMBER_JOINED,
                groupId, ownerId, invitees, Map.of("name", name), now);
        return summary(repository.findGroup(groupId).orElseThrow());
    }

    @Override
    public List<GroupSummaryDto> listGroups(String userId) {
        return repository.listGroups(userId).stream().map(this::summary).toList();
    }

    @Override
    public GroupDetailDto getGroup(String userId, String groupId) {
        GroupRecord group = requireGroup(groupId);
        requireMember(groupId, userId);
        AnnouncementDto announcement = repository.announcement(groupId)
                .map(this::announcement).orElse(null);
        boolean announcementUnread = announcement != null && repository.announcementUnread(
                groupId, userId, announcement.version());
        return new GroupDetailDto(summary(group), announcement, group.memberInviteEnabled(),
                announcementUnread);
    }

    @Override
    @Transactional
    public GroupDetailDto updateGroup(String appId, String userId, String groupId,
                                      UpdateGroupRequest request) {
        GroupRecord group = requireGroup(groupId);
        MemberRecord member = requireManager(groupId, userId);
        if (request == null) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "群资料不能为空");
        }
        String name = request.name() == null ? group.name() : validateName(request.name());
        String avatar = request.avatarUrl() == null ? group.avatarUrl() : request.avatarUrl();
        boolean inviteEnabled = request.memberInviteEnabled() == null
                ? group.memberInviteEnabled() : request.memberInviteEnabled();
        long now = time.nowMillis();
        repository.updateProfile(groupId, name, avatar, inviteEnabled, now);
        notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_PROFILE_CHANGED,
                groupId, userId, List.of(), Map.of("name", name, "avatar_url", avatar), now);
        return getGroup(userId, groupId);
    }

    @Override
    @Transactional
    public void dissolveGroup(String appId, String userId, String groupId) {
        requireOwner(groupId, userId);
        long now = time.nowMillis();
        List<String> members = repository.activeMemberIds(groupId);
        repository.dissolve(groupId, now);
        for (String member : members) {
            conversations.setReadOnly(member, ConvIds.group(groupId), true);
        }
        notifyGroup(members, GroupEvent.GroupEventType.GROUP_EVENT_TYPE_DISSOLVED,
                groupId, userId, members, Map.of(), now);
    }

    @Override
    public List<GroupMemberDto> listMembers(String userId, String groupId) {
        requireMember(groupId, userId);
        return repository.listMembers(groupId).stream().map(member -> new GroupMemberDto(groupId,
                member.userId(), member.role(), member.groupNickname(), member.mutedUntil(),
                member.mutedAll())).toList();
    }

    @Override
    @Transactional
    public void inviteMembers(String appId, String userId, String groupId, InviteMembersRequest request) {
        GroupRecord group = requireGroup(groupId);
        MemberRecord inviter = requireMember(groupId, userId);
        boolean manager = isManager(inviter);
        if (!manager && !group.memberInviteEnabled()) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "当前群不允许普通成员邀请");
        }
        List<String> targets = request == null ? List.of() : distinct(request.userIds());
        if (targets.isEmpty()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "邀请成员不能为空");
        }
        List<String> added = new ArrayList<>();
        for (String target : targets) {
            if (repository.findMember(groupId, target).map(m -> "ACTIVE".equals(m.status())).orElse(false)) {
                continue;
            }
            added.add(target);
        }
        if (added.isEmpty()) {
            return;
        }
        if (repository.activeMemberCount(groupId) + added.size() > MAX_MEMBERS) {
            throw new BizException(ErrorCodes.GROUP_FULL, "群成员已达上限");
        }
        long now = time.nowMillis();
        List<String> activated = new ArrayList<>();
        for (String target : added) {
            if (repository.insertMember(groupId, target, "MEMBER", now)) {
                activated.add(target);
            }
        }
        if (!activated.isEmpty()) {
            conversations.addGroupMembers(group.convId(), activated, now);
            notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MEMBER_JOINED,
                    groupId, userId, activated, Map.of(), now);
        }
    }

    @Override
    @Transactional
    public void removeMember(String appId, String userId, String groupId, MemberActionRequest request) {
        requireManager(groupId, userId);
        String target = request == null ? null : request.userId();
        MemberRecord targetMember = requireMember(groupId, target);
        MemberRecord operator = requireMember(groupId, userId);
        if ("OWNER".equals(targetMember.role())) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "不能移出群主");
        }
        if ("ADMIN".equals(targetMember.role()) && !"OWNER".equals(operator.role())) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "管理员不能移出其他管理员");
        }
        long now = time.nowMillis();
        if (!repository.removeMember(groupId, target, "KICKED", now)) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "成员已被移出");
        }
        conversations.setReadOnly(target, ConvIds.group(groupId), true);
        notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MEMBER_KICKED,
                groupId, userId, List.of(target), Map.of(), now);
    }

    @Override
    @Transactional
    public void updateRole(String appId, String userId, String groupId, UpdateRoleRequest request) {
        requireOwner(groupId, userId);
        if (request == null || request.userId() == null) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "目标成员不能为空");
        }
        requireMember(groupId, request.userId());
        String role = request.role() == null ? "" : request.role().toUpperCase();
        if (!"ADMIN".equals(role) && !"MEMBER".equals(role)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "角色只能是 ADMIN 或 MEMBER");
        }
        repository.updateRole(groupId, request.userId(), role);
        notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_ROLE_CHANGED,
                groupId, userId, List.of(request.userId()), Map.of("role", role), time.nowMillis());
    }

    @Override
    @Transactional
    public GroupSummaryDto transferOwner(String appId, String userId, String groupId,
                                         TransferOwnerRequest request) {
        requireOwner(groupId, userId);
        if (request == null || request.userId() == null || request.userId().equals(userId)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "请选择新的群主");
        }
        requireMember(groupId, request.userId());
        String oldRole = "ADMIN".equalsIgnoreCase(request.oldOwnerRole()) ? "ADMIN" : "MEMBER";
        long now = time.nowMillis();
        repository.transferOwner(groupId, userId, request.userId(), oldRole, now);
        notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_ROLE_CHANGED,
                groupId, userId, List.of(userId, request.userId()),
                Map.of("owner_id", request.userId(), "old_owner_role", oldRole), now);
        return summary(requireGroup(groupId));
    }

    @Override
    @Transactional
    public void mute(String appId, String userId, String groupId, MuteRequest request) {
        requireManager(groupId, userId);
        String target = request == null || request.userId() == null ? "ALL" : request.userId();
        if (!"ALL".equals(target)) {
            requireMember(groupId, target);
        }
        Long duration = request == null ? null : request.durationSeconds();
        if (duration != null && duration != 600 && duration != 3600 && duration != 86400) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "禁言时长仅支持 10 分钟、1 小时或 24 小时");
        }
        long now = time.nowMillis();
        Long until = duration == null ? null : now + duration * 1000L;
        repository.upsertMute(groupId, target, until, userId, now);
        notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MUTE_CHANGED,
                groupId, userId, List.of(target),
                until == null ? Map.of("enabled", "true") : Map.of("enabled", "true", "until", until.toString()), now);
    }

    @Override
    @Transactional
    public void unmute(String appId, String userId, String groupId, String targetUserId) {
        requireManager(groupId, userId);
        String target = targetUserId == null || targetUserId.isBlank() ? "ALL" : targetUserId;
        repository.removeMute(groupId, target, userId, time.nowMillis());
        notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MUTE_CHANGED,
                groupId, userId, List.of(target), Map.of("enabled", "false"), time.nowMillis());
    }

    @Override
    @Transactional
    public AnnouncementDto getAnnouncement(String userId, String groupId) {
        requireMember(groupId, userId);
        AnnouncementRecord announcement = repository.announcement(groupId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "群公告不存在"));
        repository.markAnnouncementRead(groupId, userId, announcement.version(), time.nowMillis());
        return announcement(announcement);
    }

    @Override
    @Transactional
    public AnnouncementDto updateAnnouncement(String appId, String userId, String groupId,
                                              AnnouncementRequest request) {
        requireManager(groupId, userId);
        if (request == null || request.content() == null || request.content().isBlank()
                || request.content().codePointCount(0, request.content().length()) > 5000) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "群公告长度必须为 1-5000 字");
        }
        long now = time.nowMillis();
        AnnouncementRecord announcement = repository.upsertAnnouncement(groupId, request.content(), userId, now);
        notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_ANNOUNCEMENT,
                groupId, userId, List.of(), Map.of("version", Long.toString(announcement.version())), now);
        return announcement(announcement);
    }

    @Override
    @Transactional
    public void updateGroupNickname(String userId, String groupId, String targetUserId, String nickname) {
        MemberRecord operator = requireMember(groupId, userId);
        String target = targetUserId == null || targetUserId.isBlank() ? userId : targetUserId;
        if (!target.equals(userId) && !isManager(operator)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "仅群主、管理员或本人可以修改群名片");
        }
        requireMember(groupId, target);
        if (nickname == null || nickname.isBlank() || nickname.codePointCount(0, nickname.length()) > 64) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "群名片长度必须为 1-64 字");
        }
        repository.updateGroupNickname(groupId, target, nickname, time.nowMillis());
        notifyGroup(repository.activeMemberIds(groupId),
                GroupEvent.GroupEventType.GROUP_EVENT_TYPE_NICKNAME_CHANGED,
                groupId, userId, List.of(target), Map.of("group_nickname", nickname), time.nowMillis());
    }

    @Override
    @Transactional
    public InviteDto createInvite(String appId, String userId, String groupId, CreateInviteRequest request) {
        requireManager(groupId, userId);
        long now = time.nowMillis();
        long ttl = request == null || request.expiresInSeconds() == null ? 7 * 86400L
                : Math.max(60, Math.min(request.expiresInSeconds(), 30 * 86400L));
        String token = Ids.newId("ginv") + "_" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomBytes(18));
        InviteRecord invite = new InviteRecord(Ids.newId("gi"), groupId, sha256(token), "LINK",
                userId, now, now + ttl * 1000L);
        repository.insertInvite(invite);
        return new InviteDto(invite.inviteId(), groupId, invite.inviteType(), token, invite.expiresAt());
    }

    @Override
    @Transactional
    public void leaveGroup(String appId, String userId, String groupId) {
        MemberRecord member = requireMember(groupId, userId);
        if ("OWNER".equals(member.role())) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "群主必须先转让群主");
        }
        long now = time.nowMillis();
        if (!repository.removeMember(groupId, userId, "LEFT", now)) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "你已不在群聊中");
        }
        conversations.setReadOnly(userId, ConvIds.group(groupId), true);
        notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MEMBER_LEFT,
                groupId, userId, List.of(userId), Map.of(), now);
    }

    @Override
    @Transactional
    public JoinResultDto joinByInvite(String appId, String userId, JoinByInviteRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "邀请令牌不能为空");
        }
        long now = time.nowMillis();
        InviteRecord invite = repository.findActiveInvite(sha256(request.token()), now)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "邀请已失效"));
        GroupRecord group = requireGroup(invite.groupId());
        var historicalMember = repository.findMember(group.groupId(), userId);
        if (historicalMember.map(member -> "ACTIVE".equals(member.status())).orElse(false)) {
            return new JoinResultDto(group.groupId(), null, "JOINED");
        }
        if (historicalMember.map(member -> "KICKED".equals(member.status())).orElse(false)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "被移出成员需重新受邀才能入群");
        }
        if (repository.activeMemberCount(group.groupId()) >= MAX_MEMBERS) {
            throw new BizException(ErrorCodes.GROUP_FULL, "群成员已达上限");
        }
        if (repository.hasPendingJoin(group.groupId(), userId)) {
            throw new BizException(ErrorCodes.DUPLICATE_APPLY, "入群申请已提交");
        }
        if (group.inviteVerification()) {
            String requestId = Ids.newId("gjr");
            repository.insertJoinRequest(requestId, group.groupId(), userId, invite.inviteId(), now);
            return new JoinResultDto(group.groupId(), requestId, "PENDING");
        }
        repository.insertMember(group.groupId(), userId, "MEMBER", now);
        conversations.createGroupConversation(appId, group.groupId(), group.convId(), group.name(),
                group.avatarUrl(), repository.activeMemberIds(group.groupId()), group.ownerId(), now);
        notifyGroup(repository.activeMemberIds(group.groupId()), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MEMBER_JOINED,
                group.groupId(), userId, List.of(userId), Map.of(), now);
        return new JoinResultDto(group.groupId(), null, "JOINED");
    }

    @Override
    @Transactional
    public JoinResultDto handleJoinRequest(String appId, String userId, String groupId, String requestId,
                                           HandleJoinRequest request) {
        requireManager(groupId, userId);
        var join = repository.findJoinRequest(requestId, groupId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "入群申请不存在"));
        if (!"PENDING".equals(join.status())) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "入群申请已处理");
        }
        long now = time.nowMillis();
        String targetStatus = request != null && request.accept() ? "ACCEPTED" : "REJECTED";
        if (!repository.finishJoinRequest(requestId, targetStatus, userId, now)) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "入群申请已处理");
        }
        if ("ACCEPTED".equals(targetStatus)) {
            if (repository.activeMemberCount(groupId) >= MAX_MEMBERS) {
                throw new BizException(ErrorCodes.GROUP_FULL, "群成员已达上限");
            }
            GroupRecord group = requireGroup(groupId);
            repository.insertMember(groupId, join.userId(), "MEMBER", now);
            conversations.createGroupConversation(appId, groupId, group.convId(), group.name(),
                    group.avatarUrl(), repository.activeMemberIds(groupId), group.ownerId(), now);
            notifyGroup(repository.activeMemberIds(groupId), GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MEMBER_JOINED,
                    groupId, userId, List.of(join.userId()), Map.of(), now);
        }
        return new JoinResultDto(groupId, requestId, targetStatus);
    }

    @Override
    public void authorizeMessage(String appId, String userId, String groupId, List<String> mentions) {
        GroupRecord group = requireGroup(groupId);
        MemberRecord member = requireMember(groupId, userId);
        if (!isManager(member) && repository.hasPendingMute(groupId, userId, time.nowMillis())) {
            throw new BizException(ErrorCodes.MUTED, "你当前已被禁言");
        }
        if (mentions != null && mentions.stream().anyMatch("ALL"::equalsIgnoreCase)) {
            if (!isManager(member)) {
                throw new BizException(ErrorCodes.NO_PERMISSION, "仅群主和管理员可以@全体成员");
            }
            long now = time.nowMillis();
            long last = mentionAllLastAt.getOrDefault(groupId, 0L);
            if (now - last < 60_000L) {
                throw new BizException(ErrorCodes.MENTION_ALL_RATE_LIMITED,
                        "60 秒内只能发送一次@全体消息");
            }
            mentionAllLastAt.put(groupId, now);
        }
    }

    @Override
    public List<String> listActiveMemberIds(String convId) {
        if (!ConvIds.isGroup(convId)) {
            return List.of();
        }
        String groupId = groupIdOf(convId);
        GroupRecord group = requireGroup(groupId);
        if (!"ACTIVE".equals(group.status())) {
            return List.of();
        }
        return repository.activeMemberIds(groupId);
    }

    @Override
    public String findGroupIdByConvId(String convId, String userId) {
        if (!ConvIds.isGroup(convId)) {
            throw new BizException(ErrorCodes.CONV_INVALID, "不是群会话");
        }
        String groupId = groupIdOf(convId);
        requireMember(groupId, userId);
        return groupId;
    }

    private GroupRecord requireGroup(String groupId) {
        GroupRecord group = repository.findGroup(groupId)
                .orElseThrow(() -> new BizException(ErrorCodes.NOT_IN_GROUP, "群聊不存在"));
        if (!"ACTIVE".equals(group.status())) {
            throw new BizException(ErrorCodes.GROUP_DISSOLVED, "群聊已解散");
        }
        return group;
    }

    private MemberRecord requireMember(String groupId, String userId) {
        MemberRecord member = repository.findMember(groupId, userId)
                .orElseThrow(() -> new BizException(ErrorCodes.NOT_IN_GROUP, "你不在群聊中"));
        if (!"ACTIVE".equals(member.status())) {
            throw new BizException(ErrorCodes.NOT_IN_GROUP, "你不在群聊中");
        }
        return member;
    }

    private MemberRecord requireManager(String groupId, String userId) {
        MemberRecord member = requireMember(groupId, userId);
        if (!isManager(member)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "需要群主或管理员权限");
        }
        return member;
    }

    private MemberRecord requireOwner(String groupId, String userId) {
        MemberRecord member = requireMember(groupId, userId);
        if (!"OWNER".equals(member.role())) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "仅群主可以执行该操作");
        }
        return member;
    }

    private boolean isManager(MemberRecord member) {
        return "OWNER".equals(member.role()) || "ADMIN".equals(member.role());
    }

    private GroupSummaryDto summary(GroupRecord group) {
        return new GroupSummaryDto(group.groupId(), group.convId(), group.name(), group.avatarUrl(),
                group.memberCount(), group.ownerId(), group.status());
    }

    private AnnouncementDto announcement(AnnouncementRecord value) {
        return new AnnouncementDto(value.groupId(), value.content(), value.operatorId(), value.version());
    }

    private void notifyGroup(List<String> recipients, GroupEvent.GroupEventType type, String groupId,
                             String operatorId, List<String> targets, Map<String, String> params, long now) {
        GroupEvent event = GroupEvent.newBuilder().setType(type).setGroupId(groupId)
                .setOperatorId(operatorId).addAllTargetUserIds(targets).putAllParams(params).build();
        for (String recipient : recipients) {
            events.append(recipient, Ids.newEventId(),
                    SyncEvent.newBuilder().setEventTime(now).setGroup(event).build());
        }
    }

    private String validateName(String value) {
        String name = value.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 128) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "群名称长度必须为 1-128 字");
        }
        return name;
    }

    private List<String> distinct(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                unique.add(value);
            }
        }
        return new ArrayList<>(unique);
    }

    private String groupIdOf(String convId) {
        return convId.substring(ConvIds.GROUP_PREFIX.length());
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("邀请令牌摘要失败", error);
        }
    }
}
