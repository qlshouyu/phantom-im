package im.phantom.openapi.adapter;

import im.phantom.api.user.ImportUserRequest;
import im.phantom.api.user.ImportUserResponse;
import im.phantom.api.user.ForceLogoutRequest;
import im.phantom.api.user.UpdateUserProfileRequest;
import im.phantom.api.user.UserProfileDto;
import im.phantom.api.user.UserService;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.openapi.security.OpenApiAuthenticationFilter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户导入接口。 */
@RestController
@RequestMapping(ApiRoutes.OPENAPI_USERS)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ApiResponse<ImportUserResponse> importUser(
            @RequestBody ImportUserRequest request,
            @RequestAttribute(OpenApiAuthenticationFilter.APP_ID_ATTRIBUTE) String appId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(userService.importUser(appId, request), requestId);
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserProfileDto> profile(@PathVariable("userId") String userId,
                                               @RequestAttribute(OpenApiAuthenticationFilter.APP_ID_ATTRIBUTE) String appId,
                                               @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(userService.getProfile(appId, userId), requestId);
    }

    @PutMapping("/{userId}")
    public ApiResponse<UserProfileDto> updateProfile(
            @PathVariable("userId") String userId,
            @RequestBody UpdateUserProfileRequest request,
            @RequestAttribute(OpenApiAuthenticationFilter.APP_ID_ATTRIBUTE) String appId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(userService.updateProfile(appId, userId, request, false), requestId);
    }

    @PostMapping("/{userId}/disable")
    public ApiResponse<Void> disable(@PathVariable("userId") String userId,
                                     @RequestAttribute(OpenApiAuthenticationFilter.APP_ID_ATTRIBUTE) String appId,
                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        userService.disable(appId, userId);
        return ApiResponse.ok(null, requestId);
    }

    @PostMapping("/{userId}/restore")
    public ApiResponse<Void> restore(@PathVariable("userId") String userId,
                                     @RequestAttribute(OpenApiAuthenticationFilter.APP_ID_ATTRIBUTE) String appId,
                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        userService.restore(appId, userId);
        return ApiResponse.ok(null, requestId);
    }

    @PostMapping("/{userId}/force-logout")
    public ApiResponse<Void> forceLogout(@PathVariable("userId") String userId,
                                         @RequestBody(required = false) ForceLogoutRequest request,
                                         @RequestAttribute(OpenApiAuthenticationFilter.APP_ID_ATTRIBUTE) String appId,
                                         @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        userService.forceLogout(appId, userId, request == null ? null : request.deviceId());
        return ApiResponse.ok(null, requestId);
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> delete(@PathVariable("userId") String userId,
                                    @RequestAttribute(OpenApiAuthenticationFilter.APP_ID_ATTRIBUTE) String appId,
                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        userService.delete(appId, userId);
        return ApiResponse.ok(null, requestId);
    }
}
