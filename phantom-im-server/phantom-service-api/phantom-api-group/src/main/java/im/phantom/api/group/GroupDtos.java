package im.phantom.api.group;

import java.util.List;

/** 群聊请求与响应 DTO。 */
public final class GroupDtos {
    private GroupDtos() {}

    public record CreateGroupRequest(String name, List<String> memberIds) {}
    public record UpdateGroupRequest(String name, String avatarUrl, Boolean memberInviteEnabled) {}
    public record InviteMembersRequest(List<String> userIds) {}
    public record MemberActionRequest(String userId) {}
    public record UpdateRoleRequest(String userId, String role) {}
    public record TransferOwnerRequest(String userId, String oldOwnerRole) {}
    public record MuteRequest(String userId, Long durationSeconds) {}
    public record AnnouncementRequest(String content) {}
    public record CreateInviteRequest(Long expiresInSeconds) {}
    public record AnnouncementDto(String groupId, String content, String operatorId, long version) {}
    public record InviteDto(String inviteId, String groupId, String inviteType, String token, Long expiresAt) {}
    public record JoinByInviteRequest(String token) {}
    public record JoinResultDto(String groupId, String requestId, String status) {}
    public record HandleJoinRequest(boolean accept) {}
    public record GroupDetailDto(GroupSummaryDto summary, AnnouncementDto announcement,
                                 boolean memberInviteEnabled, boolean announcementUnread) {}
}
