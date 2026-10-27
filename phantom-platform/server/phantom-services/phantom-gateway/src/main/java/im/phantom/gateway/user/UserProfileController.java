package im.phantom.gateway.user;

import im.phantom.api.user.UpdateUserProfileRequest;
import im.phantom.api.user.UserProfileDto;
import im.phantom.api.user.UserService;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 已认证端用户的资料读取与 SDK 写入接口。 */
@RestController
public class UserProfileController {
    private final UserService users;

    public UserProfileController(UserService users) {
        this.users = users;
    }

    @GetMapping(ApiRoutes.USERS_ME)
    public ApiResponse<UserProfileDto> me(
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(users.getProfile(appId, userId), requestId);
    }

    @PutMapping(ApiRoutes.USERS_ME)
    public ApiResponse<UserProfileDto> updateMe(
            @RequestBody UpdateUserProfileRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(users.updateProfile(appId, userId, request, true), requestId);
    }

    @GetMapping(ApiRoutes.USER)
    public ApiResponse<UserProfileDto> profile(
            @PathVariable("userId") String userId,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(users.getProfile(appId, userId), requestId);
    }
}
