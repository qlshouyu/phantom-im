package im.phantom.openapi.app;

import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.common.time.TimeProvider;
import im.phantom.openapi.security.AppSecretCipher;
import im.phantom.openapi.security.AppSecretHasher;
import im.phantom.openapi.security.OpenApiSecurityProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 本地/单应用 bootstrap 配置只用于 OpenAPI HMAC 校验；
 * 用户导入仍受数据库外键约束，因此启动时必须幂等落库应用与默认配置。
 */
public class OpenApiBootstrapInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final OpenApiSecurityProperties properties;
    private final AppSecretCipher cipher;
    private final TimeProvider time;

    public OpenApiBootstrapInitializer(JdbcTemplate jdbc, OpenApiSecurityProperties properties,
                                       AppSecretCipher cipher, TimeProvider time) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.cipher = cipher;
        this.time = time;
    }

    @Override
    public void run(ApplicationArguments args) {
        String appId = properties.getBootstrapAppId();
        String secret = properties.getBootstrapSecret();
        if (appId == null || appId.isBlank() || secret == null || secret.isBlank()) {
            return;
        }
        long now = time.nowMillis();
        jdbc.update("""
                INSERT IGNORE INTO app(app_id, name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?)
                """, appId, "OpenAPI Bootstrap 应用", now, now);
        AppFeatureConfigDto features = AppFeatureConfigDto.defaults();
        jdbc.update("""
                INSERT IGNORE INTO app_config(app_id, relation_hosted, read_receipt_enabled,
                    burn_after_read_enabled, burn_after_read_ttl_seconds, risk_check_enabled,
                    sdk_profile_write_enabled, max_devices, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, appId, features.relationHosted(), features.readReceiptEnabled(),
                features.burnAfterReadEnabled(), features.burnAfterReadTtlSeconds(),
                features.riskCheckEnabled(), features.sdkProfileWriteEnabled(),
                features.maxDevices(), now);
        jdbc.update("""
                INSERT IGNORE INTO app_secret_version(app_id, secret_version, secret_hash,
                    secret_ciphertext, status, created_at, expires_at)
                VALUES (?, 'bootstrap', ?, ?, 'ACTIVE', ?, NULL)
                """, appId, AppSecretHasher.sha256(secret), cipher.encrypt(secret), now);
    }
}
