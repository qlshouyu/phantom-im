package im.phantom.risk.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRiskRepository {
    private final JdbcTemplate jdbc;
    public JdbcRiskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<SanctionRow> activeSanction(String userId, long now) {
        return jdbc.query("""
                SELECT sanction_id, sanction_type, reason, starts_at, ends_at, status
                FROM sanction WHERE user_id=? AND status='ACTIVE'
                  AND (ends_at IS NULL OR ends_at>?) ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? Optional.of(new SanctionRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getLong(4), (Long) rs.getObject(5), rs.getString(6)))
                : Optional.empty(), userId, now);
    }

    public String userStatus(String userId) {
        return jdbc.query("SELECT status FROM app_user WHERE user_id=?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
    }

    public boolean minorMode(String userId) {
        Boolean value = jdbc.query("SELECT minor_mode FROM app_user WHERE user_id=?",
                rs -> rs.next() ? rs.getBoolean(1) : null, userId);
        return Boolean.TRUE.equals(value);
    }

    public boolean mutualFriends(String a, String b) {
        Integer count = jdbc.query("""
                SELECT (SELECT COUNT(*) FROM friend_relation WHERE user_id=? AND friend_id=?)
                     + (SELECT COUNT(*) FROM friend_relation WHERE user_id=? AND friend_id=?)
                """, rs -> rs.next() ? rs.getInt(1) : 0, a, b, b, a);
        return count != null && count == 2;
    }

    public Optional<ReportRow> pendingReport(String reporterId, String type, String targetId) {
        return jdbc.query("""
                SELECT report_id, app_id, reporter_id, target_type, target_id, reason, description,
                       snapshot_json, status, created_at, updated_at
                FROM report WHERE reporter_id=? AND target_type=? AND target_id=? AND status='PENDING'
                ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? Optional.of(mapReport(rs)) : Optional.empty(),
                reporterId, type, targetId);
    }

    public void insertReport(ReportRow report) {
        jdbc.update("""
                INSERT INTO report(report_id, app_id, reporter_id, target_type, target_id, reason,
                    description, snapshot_json, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, report.reportId(), report.appId(), report.reporterId(), report.targetType(),
                report.targetId(), report.reason(), report.description(), report.snapshotJson(),
                report.status(), report.createdAt(), report.updatedAt());
    }

    public List<ReportRow> listReports(String reporterId, int limit) {
        return jdbc.query("SELECT report_id, app_id, reporter_id, target_type, target_id, reason, description, " +
                "snapshot_json, status, created_at, updated_at FROM report WHERE reporter_id=? " +
                "ORDER BY created_at DESC LIMIT ?", (rs, row) -> mapReport(rs), reporterId, limit);
    }

    public List<ReportRow> pendingReports(String appId, int limit) {
        return jdbc.query("SELECT report_id, app_id, reporter_id, target_type, target_id, reason, description, " +
                        "snapshot_json, status, created_at, updated_at FROM report " +
                        "WHERE app_id=? AND status='PENDING' ORDER BY created_at ASC LIMIT ?",
                (rs, row) -> mapReport(rs), appId, limit);
    }

    public Optional<ReportRow> findReport(String appId, String reportId) {
        return jdbc.query("SELECT report_id, app_id, reporter_id, target_type, target_id, reason, description, " +
                        "snapshot_json, status, created_at, updated_at FROM report WHERE app_id=? AND report_id=?",
                rs -> rs.next() ? Optional.of(mapReport(rs)) : Optional.empty(), appId, reportId);
    }

    public boolean finishReport(String reportId, String status, long now) {
        return jdbc.update("UPDATE report SET status=?, updated_at=? " +
                "WHERE report_id=? AND status='PENDING'", status, now, reportId) == 1;
    }

    public String messageSnapshot(String msgId) {
        return jdbc.query("SELECT JSON_OBJECT('msg_bytes_base64', TO_BASE64(msg_bytes)) " +
                        "FROM conv_timeline WHERE msg_id=?",
                rs -> rs.next() ? rs.getString(1) : null, msgId);
    }

    public void insertSanction(SanctionRow row, String appId, String operatorId, long createdAt) {
        jdbc.update("""
                INSERT INTO sanction(sanction_id, app_id, user_id, sanction_type, reason, starts_at,
                    ends_at, status, operator_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.sanctionId(), appId, row.userId(), row.type(), row.reason(), row.startsAt(),
                row.endsAt(), row.status(), operatorId, createdAt);
    }

    public void updateUserStatus(String userId, String status, long now) {
        jdbc.update("UPDATE app_user SET status=?, updated_at=? WHERE user_id=?", status, now, userId);
    }

    public void endActiveSanctions(String userId, long now) {
        jdbc.update("UPDATE sanction SET status='ENDED' WHERE user_id=? AND status='ACTIVE'", userId);
    }

    public boolean sanctionBelongsTo(String sanctionId, String userId) {
        Integer count = jdbc.query("SELECT COUNT(*) FROM sanction WHERE sanction_id=? AND user_id=?",
                rs -> rs.next() ? rs.getInt(1) : 0, sanctionId, userId);
        return count != null && count > 0;
    }

    public void insertAppeal(String id, String sanctionId, String userId, String content, long now) {
        jdbc.update("INSERT INTO appeal(appeal_id, sanction_id, user_id, content, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'PENDING', ?, ?)", id, sanctionId, userId, content, now, now);
    }

    public List<AppealRow> appeals(String userId, String appealId) {
        String filter = appealId == null ? " AND user_id=?" : " AND appeal_id=? AND user_id=?";
        String sql = "SELECT appeal_id, sanction_id, user_id, content, status, result, created_at, updated_at " +
                "FROM appeal WHERE 1=1" + filter + " ORDER BY created_at DESC LIMIT 100";
        Object[] args = appealId == null ? new Object[]{userId} : new Object[]{appealId, userId};
        return jdbc.query(sql, (rs, row) -> new AppealRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getLong(7), rs.getLong(8)), args);
    }

    public void audit(String appId, String actorId, String action, String targetType,
                      String targetId, String result, String detailJson, long now) {
        jdbc.update("""
                INSERT INTO audit_log(audit_id, app_id, actor_id, action, target_type, target_id,
                    result, detail_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, im.phantom.common.Ids.newId("audit"), appId, actorId, action, targetType,
                targetId, result, detailJson, now);
    }

    public List<AuditRow> auditLogs(String appId, int limit) {
        return jdbc.query("""
                SELECT audit_id, actor_id, action, target_type, target_id, result, detail_json, created_at
                FROM audit_log WHERE app_id=? ORDER BY created_at DESC LIMIT ?
                """, (rs, row) -> new AuditRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)),
                appId, limit);
    }

    public void updateMinorMode(String userId, boolean enabled, long now) {
        jdbc.update("UPDATE app_user SET minor_mode=?, updated_at=? WHERE user_id=?", enabled, now, userId);
    }

    public void insertExport(String id, String appId, String userId, String status,
                             String payload, long now) {
        jdbc.update("INSERT INTO account_export(export_id, app_id, user_id, status, payload_json, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, appId, userId, status, payload, now, now);
    }

    public List<ExportRow> exports(String userId) {
        return jdbc.query("SELECT export_id, app_id, user_id, status, payload_json, created_at, updated_at " +
                "FROM account_export WHERE user_id=? ORDER BY created_at DESC LIMIT 20",
                (rs, row) -> new ExportRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)), userId);
    }

    public String exportData(String userId) {
        StringBuilder json = new StringBuilder("{\"user_id\":\"").append(escape(userId)).append("\",");
        json.append("\"profile\":");
        String profile = jdbc.query("SELECT nickname, avatar_url, ext_json FROM user_profile WHERE user_id=?",
                rs -> rs.next() ? "{\"nickname\":\"" + escape(rs.getString(1)) + "\",\"avatar_url\":\""
                        + escape(rs.getString(2)) + "\",\"ext\":" + rs.getString(3) + "}" : "null", userId);
        json.append(profile == null ? "null" : profile).append(",\"friends\":[");
        List<String> friends = jdbc.queryForList("SELECT friend_id FROM friend_relation WHERE user_id=?",
                String.class, userId);
        for (int i = 0; i < friends.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escape(friends.get(i))).append('"');
        }
        json.append("],\"groups\":[");
        List<String> groups = jdbc.queryForList("SELECT group_id FROM group_member WHERE user_id=? AND status='ACTIVE'",
                String.class, userId);
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escape(groups.get(i))).append('"');
        }
        return json.append("]}").toString();
    }

    private ReportRow mapReport(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReportRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getLong(10), rs.getLong(11));
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record SanctionRow(String sanctionId, String type, String reason, long startsAt,
                              Long endsAt, String status, String userId) {
        public SanctionRow(String sanctionId, String type, String reason, long startsAt, Long endsAt,
                           String status) {
            this(sanctionId, type, reason, startsAt, endsAt, status, null);
        }
        public SanctionRow withUser(String value) { return new SanctionRow(sanctionId, type, reason,
                startsAt, endsAt, status, value); }
    }
    public record ReportRow(String reportId, String appId, String reporterId, String targetType,
                            String targetId, String reason, String description, String snapshotJson,
                            String status, long createdAt, long updatedAt) {}
    public record AppealRow(String appealId, String sanctionId, String userId, String content,
                            String status, String result, long createdAt, long updatedAt) {}
    public record AuditRow(String auditId, String actorId, String action, String targetType,
                           String targetId, String result, String detailJson, long createdAt) {}
    public record ExportRow(String exportId, String appId, String userId, String status,
                            String payloadJson, long createdAt, long updatedAt) {}
}
