package im.phantom.relation.adapter;

import im.phantom.api.relation.BlacklistEntryDto;
import im.phantom.api.relation.BlacklistRequest;
import im.phantom.api.relation.CreateFriendGroupRequest;
import im.phantom.api.relation.CreateFriendRequestRequest;
import im.phantom.api.relation.FriendGroupDto;
import im.phantom.api.relation.FriendRequestDto;
import im.phantom.api.relation.FriendRequestPageDto;
import im.phantom.api.relation.FriendRequestResultDto;
import im.phantom.api.relation.FriendSummaryDto;
import im.phantom.api.relation.HandleFriendRequestRequest;
import im.phantom.api.relation.QrCardDto;
import im.phantom.api.relation.QrCardScanResultDto;
import im.phantom.api.relation.RelationService;
import im.phantom.api.relation.RelationSettingsDto;
import im.phantom.api.relation.ScanQrCardRequest;
import im.phantom.api.relation.UpdateFriendGroupRequest;
import im.phantom.api.relation.UpdateFriendRequest;
import im.phantom.api.relation.UpdateRelationSettingsRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 关系链 HTTP 接口：好友申请、好友设置、分组、黑名单和二维码名片。 */
@RestController
public class RelationController {

    private final RelationService service;

    public RelationController(RelationService service) {
        this.service = service;
    }

    @GetMapping(ApiRoutes.FRIENDS)
    public ApiResponse<List<FriendSummaryDto>> friends(
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.listFriends(userId), requestId);
    }

    @PutMapping(ApiRoutes.FRIEND)
    public ApiResponse<FriendSummaryDto> updateFriend(
            @PathVariable("friendId") String friendId,
            @RequestBody UpdateFriendRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.updateFriend(userId, friendId, request), requestId);
    }

    @DeleteMapping(ApiRoutes.FRIEND)
    public ApiResponse<Void> deleteFriend(
            @PathVariable("friendId") String friendId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        service.deleteFriend(userId, friendId);
        return ApiResponse.ok(null, requestId);
    }

    @GetMapping(ApiRoutes.FRIEND_SETTINGS)
    public ApiResponse<RelationSettingsDto> settings(
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.getRelationSettings(userId), requestId);
    }

    @PutMapping(ApiRoutes.FRIEND_SETTINGS)
    public ApiResponse<RelationSettingsDto> updateSettings(
            @RequestBody UpdateRelationSettingsRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.updateRelationSettings(userId, request), requestId);
    }

    @GetMapping(ApiRoutes.FRIEND_REQUESTS)
    public ApiResponse<FriendRequestPageDto> requests(
            @RequestParam(value = "include_handled", defaultValue = "true") boolean includeHandled,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.listFriendRequests(userId, includeHandled), requestId);
    }

    @PostMapping(ApiRoutes.FRIEND_REQUESTS)
    public ApiResponse<FriendRequestResultDto> createRequest(
            @RequestBody CreateFriendRequestRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.createFriendRequest(appId, userId, request), requestId);
    }

    @PostMapping(ApiRoutes.FRIEND_REQUEST)
    public ApiResponse<FriendRequestDto> handleRequest(
            @PathVariable("requestId") String friendRequestId,
            @RequestBody HandleFriendRequestRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.handleFriendRequest(appId, userId, friendRequestId, request.action()), requestId);
    }

    @GetMapping(ApiRoutes.FRIEND_GROUPS)
    public ApiResponse<List<FriendGroupDto>> groups(
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.listFriendGroups(userId), requestId);
    }

    @PostMapping(ApiRoutes.FRIEND_GROUPS)
    public ApiResponse<FriendGroupDto> createGroup(
            @RequestBody CreateFriendGroupRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.createFriendGroup(userId, request.name()), requestId);
    }

    @PutMapping(ApiRoutes.FRIEND_GROUP)
    public ApiResponse<FriendGroupDto> renameGroup(
            @PathVariable("groupId") String groupId,
            @RequestBody UpdateFriendGroupRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.renameFriendGroup(userId, groupId, request.name()), requestId);
    }

    @DeleteMapping(ApiRoutes.FRIEND_GROUP)
    public ApiResponse<Void> deleteGroup(
            @PathVariable("groupId") String groupId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        service.deleteFriendGroup(userId, groupId);
        return ApiResponse.ok(null, requestId);
    }

    @GetMapping(ApiRoutes.BLACKLIST)
    public ApiResponse<List<BlacklistEntryDto>> blacklist(
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.listBlacklist(userId), requestId);
    }

    @PostMapping(ApiRoutes.BLACKLIST)
    public ApiResponse<Void> block(
            @RequestBody BlacklistRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        service.blockUser(appId, userId, request.userId());
        return ApiResponse.ok(null, requestId);
    }

    @DeleteMapping(ApiRoutes.BLACKLIST)
    public ApiResponse<Void> unblock(
            @RequestBody BlacklistRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        service.unblockUser(userId, request.userId());
        return ApiResponse.ok(null, requestId);
    }

    @GetMapping(ApiRoutes.FRIEND_QR)
    public ApiResponse<QrCardDto> qrCard(
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.getOrCreateQrCard(userId), requestId);
    }

    @PostMapping(ApiRoutes.FRIEND_QR_REFRESH)
    public ApiResponse<QrCardDto> refreshQrCard(
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.refreshQrCard(userId), requestId);
    }

    @PostMapping(ApiRoutes.FRIEND_QR_SCAN)
    public ApiResponse<QrCardScanResultDto> scanQrCard(
            @RequestBody ScanQrCardRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.scanQrCard(appId, userId, request.token()), requestId);
    }
}
