package im.phantom.risk.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.route.GatewayDeliveryService;
import im.phantom.api.route.RouteTableService;
import im.phantom.common.BizException;
import im.phantom.common.Envelopes;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.common.risk.UserSafetyChecker;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.KickPush;
import im.phantom.proto.v1.ReportEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.risk.RiskProperties;
import im.phantom.risk.infra.JdbcRiskRepository;
import im.phantom.risk.infra.JdbcRiskRepository.AppealRow;
import im.phantom.risk.infra.JdbcRiskRepository.AuditRow;
import im.phantom.risk.infra.JdbcRiskRepository.ExportRow;
import im.phantom.risk.infra.JdbcRiskRepository.ReportRow;
import im.phantom.risk.infra.JdbcRiskRepository.SanctionRow;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 举报、处置、申诉、安全检查和数据导出用例。 */
@Service
public class RiskAppService implements UserSafetyChecker {

    private static final Set<String> TARGETS = Set.of("MESSAGE", "USER", "GROUP");
    private static final Set<String> REASONS = Set.of("SPAM", "ABUSE", "PORN", "FRAUD",
            "ILLEGAL", "MINOR", "OTHER");
    private final JdbcRiskRepository repository;
    private final RouteTableService routes;
    private final GatewayDeliveryService gateway;
    private final RiskProperties properties;
    private final ObjectMapper objectMapper;
    private final ReportEventWriter reportEvents;

    public RiskAppService(JdbcRiskRepository repository, RouteTableService routes,
                          GatewayDeliveryService gateway, RiskProperties properties,
                          ObjectMapper objectMapper) {
        this(repository, routes, gateway, properties, objectMapper, null);
    }

    @Autowired
    public RiskAppService(JdbcRiskRepository repository, RouteTableService routes,
                          GatewayDeliveryService gateway, RiskProperties properties,
                          ObjectMapper objectMapper, ReportEventWriter reportEvents) {
        this.repository = repository;
        this.routes = routes;
        this.gateway = gateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.reportEvents = reportEvents;
    }

    @Transactional
    public ReportRow report(String appId, String reporterId, String targetType, String targetId,
                            String reason, String description) {
        String type = targetType == null ? "" : targetType.toUpperCase();
        if (!TARGETS.contains(type) || targetId == null || targetId.isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "举报目标非法");
        }
        String normalizedReason = reason == null ? "OTHER" : reason.toUpperCase();
        if (!REASONS.contains(normalizedReason)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "举报理由非法");
        }
        if (description != null && description.codePointCount(0, description.length()) > 1000) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "举报说明超过 1000 字");
        }
        var duplicate = repository.pendingReport(reporterId, type, targetId);
        if (duplicate.isPresent()) return duplicate.get();
        long now = System.currentTimeMillis();
        String snapshot = "MESSAGE".equals(type) ? repository.messageSnapshot(targetId)
                : json(Map.of("target_type", type, "target_id", targetId));
        ReportRow row = new ReportRow(Ids.newId("report"), appId, reporterId, type, targetId,
                normalizedReason, description == null ? "" : description, snapshot, "PENDING", now, now);
        repository.insertReport(row);
        repository.audit(appId, reporterId, "REPORT_SUBMIT", type, targetId, "PENDING",
                json(Map.of("reason", normalizedReason)), now);
        return row;
    }

    public List<ReportRow> reports(String reporterId, int limit) {
        return repository.listReports(reporterId, Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100)));
    }

    public List<ReportRow> pendingReports(String appId, int limit) {
        return repository.pendingReports(appId, Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100)));
    }

    @Transactional
    public ReportRow handleReport(String appId, String adminId, String reportId, String action) {
        String status = action == null ? "" : action.toUpperCase();
        if (!Set.of("RESOLVED", "REJECTED").contains(status)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "举报处理动作非法");
        }
        ReportRow report = repository.findReport(appId, reportId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "举报单不存在"));
        if (!"PENDING".equals(report.status())) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "举报单已处理");
        }
        long now = System.currentTimeMillis();
        if (!repository.finishReport(reportId, status, now)) {
            throw new BizException(ErrorCodes.ALREADY_HANDLED, "举报单已处理");
        }
        ReportRow updated = new ReportRow(report.reportId(), report.appId(), report.reporterId(),
                report.targetType(), report.targetId(), report.reason(), report.description(),
                report.snapshotJson(), status, report.createdAt(), now);
        if (reportEvents != null) {
            reportEvents.append(report.reporterId(), Ids.newEventId(), SyncEvent.newBuilder()
                    .setEventTime(now).setReport(ReportEvent.newBuilder()
                            .setReportId(reportId).setTargetType(report.targetType())
                            .setTargetId(report.targetId()).setStatus(status)
                            .setTemplateId("report.resolved").setUpdatedAt(now).build()).build());
        }
        repository.audit(appId, adminId, "REPORT_" + status, report.targetType(), report.targetId(),
                "SUCCESS", json(Map.of("report_id", reportId, "action", status)), now);
        return updated;
    }

    @Override
    public void assertCanSend(String appId, String senderId) {
        String status = repository.userStatus(senderId);
        if ("BANNED".equals(status)) throw new BizException(ErrorCodes.ACCOUNT_BANNED, "账号已被封禁");
        if ("DISABLED".equals(status) || "DELETED".equals(status)) {
            throw new BizException(ErrorCodes.ACCOUNT_RESTRICTED, "账号不可用");
        }
        SanctionRow sanction = repository.activeSanction(senderId, System.currentTimeMillis()).orElse(null);
        if (sanction == null) return;
        if ("BAN".equals(sanction.type())) throw new BizException(ErrorCodes.ACCOUNT_BANNED, "账号已被封禁");
        if ("MUTE".equals(sanction.type())) throw new BizException(ErrorCodes.ACCOUNT_RESTRICTED, "账号已被禁言");
    }

    @Override
    public void assertCanReceive(String appId, String senderId, String receiverId) {
        if (repository.minorMode(receiverId) && !repository.mutualFriends(senderId, receiverId)) {
            throw new BizException(ErrorCodes.MINOR_MODE_FORBIDDEN, "未成年人模式仅允许好友消息");
        }
    }

    @Transactional
    public SanctionRow sanction(String appId, String adminId, String userId, String type,
                                String reason, Long durationSeconds) {
        String normalized = type == null ? "" : type.toUpperCase();
        if (!Set.of("WARNING", "MUTE", "BAN").contains(normalized)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "处置类型非法");
        }
        if (repository.userStatus(userId) == null) {
            throw new BizException(ErrorCodes.USER_NOT_FOUND, "用户不存在");
        }
        long now = System.currentTimeMillis();
        Long endsAt = durationSeconds == null ? null : now + Math.max(1, durationSeconds) * 1000L;
        SanctionRow row = new SanctionRow(Ids.newId("sanction"), normalized,
                reason == null ? "" : reason, now, endsAt, "ACTIVE", userId);
        repository.insertSanction(row, appId, adminId, now);
        if ("BAN".equals(normalized)) {
            repository.updateUserStatus(userId, "BANNED", now);
            kickAll(userId, KickPush.KickReason.KICK_REASON_BANNED);
        }
        repository.audit(appId, adminId, "SANCTION_" + normalized, "USER", userId, "SUCCESS",
                json(Map.of("reason", row.reason(), "ends_at", endsAt == null ? 0 : endsAt)), now);
        return row;
    }

    @Transactional
    public void liftSanction(String appId, String adminId, String userId) {
        long now = System.currentTimeMillis();
        repository.endActiveSanctions(userId, now);
        if ("BANNED".equals(repository.userStatus(userId))) {
            repository.updateUserStatus(userId, "ACTIVE", now);
        }
        repository.audit(appId, adminId, "SANCTION_LIFT", "USER", userId, "SUCCESS", "{}", now);
    }

    @Transactional
    public AppealRow appeal(String userId, String sanctionId, String content) {
        if (sanctionId == null || content == null || content.isBlank()
                || content.codePointCount(0, content.length()) > 5000) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "申诉内容非法");
        }
        if (!repository.sanctionBelongsTo(sanctionId, userId)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "无权申诉该处置");
        }
        String appealId = Ids.newId("appeal");
        repository.insertAppeal(appealId, sanctionId, userId, content, System.currentTimeMillis());
        return repository.appeals(userId, appealId).getFirst();
    }

    public List<AppealRow> appeals(String userId, String appealId) {
        return repository.appeals(userId, appealId);
    }

    public List<AuditRow> auditLogs(String appId, String adminToken, int limit) {
        if (adminToken == null || !adminToken.equals(properties.getAdminToken())) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "需要管理员权限");
        }
        return repository.auditLogs(appId, Math.max(1, Math.min(limit, 200)));
    }

    @Transactional
    public List<ExportRow> export(String appId, String userId) {
        long now = System.currentTimeMillis();
        String payload = repository.exportData(userId);
        repository.insertExport(Ids.newId("export"), appId, userId, "READY", payload, now);
        repository.audit(appId, userId, "DATA_EXPORT", "USER", userId, "SUCCESS", "{}", now);
        return repository.exports(userId);
    }

    public List<ExportRow> exports(String userId) { return repository.exports(userId); }

    @Transactional
    public void minorMode(String appId, String userId, boolean enabled) {
        repository.updateMinorMode(userId, enabled, System.currentTimeMillis());
        repository.audit(appId, userId, enabled ? "MINOR_MODE_ENABLE" : "MINOR_MODE_DISABLE",
                "USER", userId, "SUCCESS", "{}", System.currentTimeMillis());
    }

    private void kickAll(String userId, KickPush.KickReason reason) {
        var envelope = Envelopes.push(Command.COMMAND_KICK_PUSH,
                KickPush.newBuilder().setReason(reason).build());
        for (var route : routes.query(userId)) {
            gateway.send(route.gatewayId(), route.connId(), envelope);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { return "{}"; }
    }
}
