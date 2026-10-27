package im.phantom.web.auth;

import im.phantom.web.common.ApiResponse;
import im.phantom.web.common.RequestIdFilter;
import im.phantom.web.config.WebImProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeConfigController {
    private final WebImProperties properties;

    public RuntimeConfigController(WebImProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/runtime-config")
    public ApiResponse<RuntimeConfigResponse> runtimeConfig(HttpServletRequest request) {
        Object id = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return ApiResponse.ok(new RuntimeConfigResponse(properties.appId(),
                properties.phantomWsUrl(), properties.proxyBaseUrl(), properties.testMode()),
                id == null ? null : id.toString());
    }

    public record RuntimeConfigResponse(String appId, String wsUrl, String proxyBaseUrl,
                                        boolean testMode) {
    }
}
