package im.phantom.openapi.app;

import im.phantom.api.config.AppFeatureConfigDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JdbcTemplate 应用仓库。 */
@Repository
public class JdbcApplicationRepository implements ApplicationRepository {

    private final JdbcTemplate jdbc;

    public JdbcApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(ApplicationRecord app, AppFeatureConfigDto config, SecretRecord secret) {
        jdbc.update("INSERT INTO app(app_id, name, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                app.appId(), app.name(), app.status(), app.createdAt(), app.updatedAt());
        jdbc.update("""
                INSERT INTO app_config(app_id, relation_hosted, read_receipt_enabled, burn_after_read_enabled,
                    burn_after_read_ttl_seconds, sdk_profile_write_enabled, max_devices, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, app.appId(), config.relationHosted(), config.readReceiptEnabled(), config.burnAfterReadEnabled(),
                config.burnAfterReadTtlSeconds(), config.sdkProfileWriteEnabled(), config.maxDevices(), app.updatedAt());
        jdbc.update("""
                INSERT INTO app_secret_version(app_id, secret_version, secret_hash, secret_ciphertext, status,
                    created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, secret.appId(), secret.version(), secret.hash(), secret.ciphertext(), secret.status(),
                secret.createdAt(), secret.expiresAt());
    }

    @Override
    public Optional<ApplicationRecord> findById(String appId) {
        return jdbc.query("""
                SELECT a.app_id, a.name, a.status, a.created_at, a.updated_at,
                       c.relation_hosted, c.read_receipt_enabled, c.burn_after_read_enabled,
                       c.burn_after_read_ttl_seconds, c.sdk_profile_write_enabled, c.max_devices
                FROM app a JOIN app_config c ON c.app_id=a.app_id WHERE a.app_id=?
                """, rs -> rs.next() ? Optional.of(new ApplicationRecord(
                rs.getString("app_id"), rs.getString("name"), rs.getString("status"),
                rs.getLong("created_at"), rs.getLong("updated_at"), new AppFeatureConfigDto(
                rs.getBoolean("relation_hosted"), rs.getBoolean("read_receipt_enabled"),
                rs.getBoolean("burn_after_read_enabled"), rs.getInt("burn_after_read_ttl_seconds"),
                rs.getBoolean("sdk_profile_write_enabled"), rs.getInt("max_devices")))) : Optional.empty(), appId);
    }

    @Override
    public List<SecretRecord> findSecrets(String appId) {
        return jdbc.query("""
                SELECT app_id, secret_version, secret_hash, secret_ciphertext, status, created_at, expires_at
                FROM app_secret_version WHERE app_id=? ORDER BY created_at DESC
                """, (rs, rowNum) -> new SecretRecord(rs.getString("app_id"), rs.getString("secret_version"),
                rs.getString("secret_hash"), rs.getBytes("secret_ciphertext"), rs.getString("status"),
                rs.getLong("created_at"), (Long) rs.getObject("expires_at")), appId);
    }

    @Override
    public void insertSecret(SecretRecord secret) {
        jdbc.update("""
                INSERT INTO app_secret_version(app_id, secret_version, secret_hash, secret_ciphertext, status,
                    created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, secret.appId(), secret.version(), secret.hash(), secret.ciphertext(), secret.status(),
                secret.createdAt(), secret.expiresAt());
    }

    @Override
    public void updateSecretStatus(String appId, String version, String status, Long expiresAt) {
        jdbc.update("""
                UPDATE app_secret_version SET status=?, expires_at=? WHERE app_id=? AND secret_version=?
                """, status, expiresAt, appId, version);
    }

    @Override
    public List<SecretRecord> findUsableSecrets(String appId, long nowMillis) {
        return jdbc.query("""
                SELECT app_id, secret_version, secret_hash, secret_ciphertext, status, created_at, expires_at
                FROM app_secret_version
                WHERE app_id=? AND status IN ('ACTIVE','GRACE') AND (expires_at IS NULL OR expires_at>?)
                ORDER BY created_at DESC
                """, (rs, rowNum) -> new SecretRecord(rs.getString("app_id"), rs.getString("secret_version"),
                rs.getString("secret_hash"), rs.getBytes("secret_ciphertext"), rs.getString("status"),
                rs.getLong("created_at"), (Long) rs.getObject("expires_at")), appId, nowMillis);
    }

    @Override
    public void updateFeatures(String appId, AppFeatureConfigDto config, long updatedAt) {
        jdbc.update("""
                UPDATE app_config SET relation_hosted=?, read_receipt_enabled=?, burn_after_read_enabled=?,
                    burn_after_read_ttl_seconds=?, sdk_profile_write_enabled=?, max_devices=?, updated_at=?
                WHERE app_id=?
                """, config.relationHosted(), config.readReceiptEnabled(), config.burnAfterReadEnabled(),
                config.burnAfterReadTtlSeconds(), config.sdkProfileWriteEnabled(), config.maxDevices(),
                updatedAt, appId);
    }
}
