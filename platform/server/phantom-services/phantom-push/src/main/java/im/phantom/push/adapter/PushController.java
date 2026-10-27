package im.phantom.push.adapter;

import im.phantom.api.device.PushTokenDto;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import im.phantom.push.app.JdbcPushRepository;
import im.phantom.push.app.PushSettingsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PushController {
    private final PushSettingsService settings;

    public PushController(PushSettingsService settings) { this.settings = settings; }

    public record SettingsRequest(Boolean pushEnabled, Boolean previewEnabled) {}
    public record PrivacyLinksRequest(String privacyPolicyUrl, String termsUrl) {}

    @PostMapping(ApiRoutes.PUSH_TOKENS)
    public ApiResponse<Void> register(@RequestBody PushTokenDto request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        settings.registerToken(userId, request);
        return ApiResponse.ok(null, requestId);
    }

    @DeleteMapping(ApiRoutes.PUSH_TOKENS)
    public ApiResponse<Void> unregister(@RequestBody PushTokenDto request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        settings.unregisterToken(userId, request.token());
        return ApiResponse.ok(null, requestId);
    }

    @GetMapping(ApiRoutes.PUSH_SETTINGS)
    public ApiResponse<JdbcPushRepository.Settings> get(@RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(settings.getSettings(userId), requestId);
    }

    @PutMapping(ApiRoutes.PUSH_SETTINGS)
    public ApiResponse<JdbcPushRepository.Settings> update(@RequestBody SettingsRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        JdbcPushRepository.Settings current = settings.getSettings(userId);
        return ApiResponse.ok(settings.updateSettings(userId,
                request.pushEnabled() == null ? current.pushEnabled() : request.pushEnabled(),
                request.previewEnabled() == null ? current.previewEnabled() : request.previewEnabled()), requestId);
    }

    @GetMapping(ApiRoutes.PRIVACY_LINKS)
    public ApiResponse<JdbcPushRepository.Settings> privacy(@RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(settings.privacyLinks(userId), requestId);
    }

    @PutMapping(ApiRoutes.PRIVACY_LINKS)
    public ApiResponse<JdbcPushRepository.Settings> updatePrivacy(@RequestBody PrivacyLinksRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(settings.updatePrivacyLinks(userId,
                request.privacyPolicyUrl(), request.termsUrl()), requestId);
    }
}
