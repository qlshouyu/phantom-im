package im.phantom.api.group;

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

import java.util.List;

/** 群聊服务间契约。 */
public interface GroupService {
    GroupSummaryDto createGroup(String appId, String ownerId, CreateGroupRequest request);
    List<GroupSummaryDto> listGroups(String userId);
    GroupDetailDto getGroup(String userId, String groupId);
    GroupDetailDto updateGroup(String appId, String userId, String groupId, UpdateGroupRequest request);
    void dissolveGroup(String appId, String userId, String groupId);
    List<GroupMemberDto> listMembers(String userId, String groupId);
    void inviteMembers(String appId, String userId, String groupId, InviteMembersRequest request);
    void removeMember(String appId, String userId, String groupId, MemberActionRequest request);
    void updateRole(String appId, String userId, String groupId, UpdateRoleRequest request);
    GroupSummaryDto transferOwner(String appId, String userId, String groupId, TransferOwnerRequest request);
    void mute(String appId, String userId, String groupId, MuteRequest request);
    void unmute(String appId, String userId, String groupId, String targetUserId);
    AnnouncementDto getAnnouncement(String userId, String groupId);
    AnnouncementDto updateAnnouncement(String appId, String userId, String groupId, AnnouncementRequest request);
    void updateGroupNickname(String userId, String groupId, String targetUserId, String nickname);
    InviteDto createInvite(String appId, String userId, String groupId, CreateInviteRequest request);
    void leaveGroup(String appId, String userId, String groupId);
    JoinResultDto joinByInvite(String appId, String userId, JoinByInviteRequest request);
    JoinResultDto handleJoinRequest(String appId, String userId, String groupId, String requestId,
                                    HandleJoinRequest request);
    void authorizeMessage(String appId, String userId, String groupId, List<String> mentions);
    List<String> listActiveMemberIds(String convId);
    String findGroupIdByConvId(String convId, String userId);
}
