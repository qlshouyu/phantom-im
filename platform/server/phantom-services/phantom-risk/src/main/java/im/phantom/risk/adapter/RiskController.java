package im.phantom.risk.adapter;

import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import im.phantom.risk.app.RiskAppService;
import im.phantom.risk.infra.JdbcRiskRepository.AppealRow;
import im.phantom.risk.infra.JdbcRiskRepository.ExportRow;
import im.phantom.risk.infra.JdbcRiskRepository.ReportRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class RiskController {
    private final RiskAppService risk;
    public RiskController(RiskAppService risk) { this.risk = risk; }

    public record ReportRequest(String targetType, String targetId, String reason, String description) {}
    public record AppealRequest(String sanctionId, String content) {}
    public record MinorModeRequest(boolean enabled) {}

    @PostMapping(ApiRoutes.REPORTS)
    public ApiResponse<ReportRow> report(@RequestBody ReportRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(risk.report(appId, userId, request.targetType(), request.targetId(),
                request.reason(), request.description()), requestId);
    }

    @GetMapping(ApiRoutes.REPORTS)
    public ApiResponse<List<ReportRow>> reports(@RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(risk.reports(userId, limit), requestId);
    }

    @PostMapping(ApiRoutes.APPEALS)
    public ApiResponse<AppealRow> appeal(@RequestBody AppealRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(risk.appeal(userId, request.sanctionId(), request.content()), requestId);
    }

    @GetMapping(ApiRoutes.APPEALS)
    public ApiResponse<List<AppealRow>> appeals(@RequestParam(value = "appeal_id", required = false) String appealId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(risk.appeals(userId, appealId), requestId);
    }

    @PostMapping(ApiRoutes.ACCOUNT_EXPORT)
    public ApiResponse<List<ExportRow>> exportRequest(
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(risk.export(appId, userId), requestId);
    }

    @GetMapping(ApiRoutes.ACCOUNT_EXPORT)
    public ApiResponse<List<ExportRow>> exports(@RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(risk.exports(userId), requestId);
    }

    @PostMapping(ApiRoutes.ACCOUNT_MINOR_MODE)
    public ApiResponse<Void> minorMode(@RequestBody MinorModeRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        risk.minorMode(appId, userId, request.enabled());
        return ApiResponse.ok(null, requestId);
    }
}
