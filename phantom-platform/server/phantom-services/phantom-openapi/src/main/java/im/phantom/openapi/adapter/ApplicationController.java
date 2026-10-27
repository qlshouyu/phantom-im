package im.phantom.openapi.adapter;

import im.phantom.api.config.ApplicationDetailDto;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.api.config.CreateApplicationRequest;
import im.phantom.api.config.CreateApplicationResponse;
import im.phantom.api.config.RotateSecretRequest;
import im.phantom.api.config.RotateSecretResponse;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.openapi.app.ApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 应用管理接口。 */
@RestController
@RequestMapping(ApiRoutes.OPENAPI_APPS)
public class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<CreateApplicationResponse> create(@RequestBody CreateApplicationRequest request,
                                                          @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.create(request.name()), requestId);
    }

    @GetMapping("/{appId}")
    public ApiResponse<ApplicationDetailDto> detail(@PathVariable("appId") String appId,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.detail(appId), requestId);
    }

    @PostMapping("/{appId}/secrets/rotate")
    public ApiResponse<RotateSecretResponse> rotateSecret(@PathVariable("appId") String appId,
                                                          @RequestBody RotateSecretRequest request,
                                                          @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.rotateSecret(appId, request.graceDays()), requestId);
    }

    @GetMapping("/{appId}/config")
    public ApiResponse<AppFeatureConfigDto> features(@PathVariable("appId") String appId,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.features(appId), requestId);
    }

    @PutMapping("/{appId}/config")
    public ApiResponse<AppFeatureConfigDto> updateFeatures(@PathVariable("appId") String appId,
                                                           @RequestBody AppFeatureConfigDto features,
                                                           @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.updateFeatures(appId, features), requestId);
    }
}
