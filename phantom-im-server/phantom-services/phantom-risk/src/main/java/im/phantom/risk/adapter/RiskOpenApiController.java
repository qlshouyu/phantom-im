package im.phantom.risk.adapter;

import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.risk.app.RiskAppService;
import im.phantom.risk.infra.JdbcRiskRepository.AuditRow;
import im.phantom.risk.infra.JdbcRiskRepository.SanctionRow;
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
public class RiskOpenApiController {
    private static final String APP_ID_ATTRIBUTE = "phantom.appId";
    private final RiskAppService risk;
    public RiskOpenApiController(RiskAppService risk) { this.risk = risk; }

    public record SanctionRequest(String userId, String type, String reason, Long durationSeconds) {}
    public record ReportHandleRequest(String action) {}

    @PostMapping(ApiRoutes.OPENAPI_RISK_SANCTIONS)
    public ApiResponse<SanctionRow> sanction(@RequestBody SanctionRequest request,
            @RequestAttribute(APP_ID_ATTRIBUTE) String appId,
            @RequestHeader(value = "X-Admin-Id", defaultValue = "openapi") String adminId) {
        return ApiResponse.ok(risk.sanction(appId, adminId, request.userId(), request.type(),
                request.reason(), request.durationSeconds()), null);
    }

    @PostMapping(ApiRoutes.OPENAPI_RISK_APPEALS)
    public ApiResponse<Void> lift(@RequestBody SanctionRequest request,
            @RequestAttribute(APP_ID_ATTRIBUTE) String appId,
            @RequestHeader(value = "X-Admin-Id", defaultValue = "openapi") String adminId) {
        risk.liftSanction(appId, adminId, request.userId());
        return ApiResponse.ok(null, null);
    }

    @GetMapping(ApiRoutes.OPENAPI_RISK_AUDIT)
    public ApiResponse<List<AuditRow>> audit(
            @RequestAttribute(APP_ID_ATTRIBUTE) String appId,
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ApiResponse.ok(risk.auditLogs(appId, adminToken, limit), null);
    }

    @GetMapping(ApiRoutes.OPENAPI_RISK_REPORTS)
    public ApiResponse<List<ReportRow>> reports(
            @RequestAttribute(APP_ID_ATTRIBUTE) String appId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(risk.pendingReports(appId, limit), null);
    }

    @PostMapping(ApiRoutes.OPENAPI_RISK_REPORTS)
    public ApiResponse<ReportRow> handleReport(@RequestBody ReportHandleRequest request,
            @RequestParam("report_id") String reportId,
            @RequestAttribute(APP_ID_ATTRIBUTE) String appId,
            @RequestHeader(value = "X-Admin-Id", defaultValue = "openapi") String adminId) {
        return ApiResponse.ok(risk.handleReport(appId, adminId, reportId, request.action()), null);
    }
}
