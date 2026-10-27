package im.phantom.group.adapter;

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
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 群聊管理 HTTP API。 */
@RestController
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @PostMapping(ApiRoutes.GROUPS)
    public ApiResponse<GroupSummaryDto> create(@RequestBody CreateGroupRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.createGroup(appId, userId, request), requestId);
    }

    @GetMapping(ApiRoutes.GROUPS)
    public ApiResponse<List<GroupSummaryDto>> list(@RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.listGroups(userId), requestId);
    }

    @GetMapping(ApiRoutes.GROUP)
    public ApiResponse<GroupDetailDto> detail(@PathVariable("groupId") String groupId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.getGroup(userId, groupId), requestId);
    }

    @PutMapping(ApiRoutes.GROUP)
    public ApiResponse<GroupDetailDto> update(@PathVariable("groupId") String groupId,
            @RequestBody UpdateGroupRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.updateGroup(appId, userId, groupId, request), requestId);
    }

    @DeleteMapping(ApiRoutes.GROUP)
    public ApiResponse<Void> dissolve(@PathVariable("groupId") String groupId,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        groups.dissolveGroup(appId, userId, groupId);
        return ApiResponse.ok(null, requestId);
    }

    @GetMapping(ApiRoutes.GROUP_MEMBERS)
    public ApiResponse<List<GroupMemberDto>> members(@PathVariable("groupId") String groupId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.listMembers(userId, groupId), requestId);
    }

    @PostMapping(ApiRoutes.GROUP_MEMBERS)
    public ApiResponse<Void> invite(@PathVariable("groupId") String groupId,
            @RequestBody InviteMembersRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        groups.inviteMembers(appId, userId, groupId, request);
        return ApiResponse.ok(null, requestId);
    }

    @DeleteMapping(ApiRoutes.GROUP_MEMBERS)
    public ApiResponse<Void> remove(@PathVariable("groupId") String groupId,
            @RequestParam("user_id") String targetUserId,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        groups.removeMember(appId, userId, groupId, new MemberActionRequest(targetUserId));
        return ApiResponse.ok(null, requestId);
    }

    @PutMapping(ApiRoutes.GROUP_MEMBER_ROLE)
    public ApiResponse<Void> role(@PathVariable("groupId") String groupId,
            @PathVariable("userId") String targetUserId, @RequestBody UpdateRoleRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        groups.updateRole(appId, userId, groupId,
                new UpdateRoleRequest(targetUserId, request.role()));
        return ApiResponse.ok(null, requestId);
    }

    @PutMapping(ApiRoutes.GROUP_MEMBER_NICKNAME)
    public ApiResponse<Void> nickname(@PathVariable("groupId") String groupId,
            @PathVariable("userId") String targetUserId, @RequestBody Map<String, String> body,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String nickname = body.get("nickname");
        groups.updateGroupNickname(userId, groupId, targetUserId, nickname);
        return ApiResponse.ok(null, requestId);
    }

    @PostMapping(ApiRoutes.GROUP_TRANSFER)
    public ApiResponse<GroupSummaryDto> transfer(@PathVariable("groupId") String groupId,
            @RequestBody TransferOwnerRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.transferOwner(appId, userId, groupId, request), requestId);
    }

    @PostMapping(ApiRoutes.GROUP_INVITES)
    public ApiResponse<InviteDto> inviteLink(@PathVariable("groupId") String groupId,
            @RequestBody(required = false) CreateInviteRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.createInvite(appId, userId, groupId, request), requestId);
    }

    @GetMapping(ApiRoutes.GROUP_ANNOUNCEMENT)
    public ApiResponse<AnnouncementDto> announcement(@PathVariable("groupId") String groupId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.getAnnouncement(userId, groupId), requestId);
    }

    @PutMapping(ApiRoutes.GROUP_ANNOUNCEMENT)
    public ApiResponse<AnnouncementDto> updateAnnouncement(@PathVariable("groupId") String groupId,
            @RequestBody AnnouncementRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.updateAnnouncement(appId, userId, groupId, request), requestId);
    }

    @PostMapping(ApiRoutes.GROUP_MUTES)
    public ApiResponse<Void> mute(@PathVariable("groupId") String groupId, @RequestBody MuteRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        groups.mute(appId, userId, groupId, request);
        return ApiResponse.ok(null, requestId);
    }

    @DeleteMapping(ApiRoutes.GROUP_MUTES)
    public ApiResponse<Void> unmute(@PathVariable("groupId") String groupId,
            @RequestParam(value = "user_id", required = false) String targetUserId,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        groups.unmute(appId, userId, groupId, targetUserId);
        return ApiResponse.ok(null, requestId);
    }

    @DeleteMapping(ApiRoutes.GROUP_LEAVE)
    public ApiResponse<Void> leave(@PathVariable("groupId") String groupId,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        groups.leaveGroup(appId, userId, groupId);
        return ApiResponse.ok(null, requestId);
    }

    @PostMapping(ApiRoutes.GROUP_JOIN)
    public ApiResponse<JoinResultDto> join(@RequestBody JoinByInviteRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.joinByInvite(appId, userId, request), requestId);
    }

    @PostMapping(ApiRoutes.GROUP_JOIN_REQUEST)
    public ApiResponse<JoinResultDto> handleJoinRequest(@PathVariable("groupId") String groupId,
            @PathVariable("requestId") String requestIdValue, @RequestBody HandleJoinRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(groups.handleJoinRequest(appId, userId, groupId, requestIdValue, request), requestId);
    }
}
