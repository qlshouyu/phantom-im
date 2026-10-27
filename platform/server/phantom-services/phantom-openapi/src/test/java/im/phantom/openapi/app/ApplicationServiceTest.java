package im.phantom.openapi.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import im.phantom.openapi.security.AppSecretCipher;
import im.phantom.api.config.AppFeatureConfigDto;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationServiceTest {

    private JdbcApplicationRepository repository;
    private ApplicationService service;
    private AppSecretCipher cipher;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:openapi-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE app(app_id VARCHAR(64) PRIMARY KEY, name VARCHAR(128), status VARCHAR(24),
                  created_at BIGINT, updated_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE app_config(app_id VARCHAR(64) PRIMARY KEY, relation_hosted BOOLEAN,
                  read_receipt_enabled BOOLEAN, burn_after_read_enabled BOOLEAN,
                  burn_after_read_ttl_seconds INT, risk_check_enabled BOOLEAN,
                  sdk_profile_write_enabled BOOLEAN,
                  max_devices INT, updated_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE app_secret_version(app_id VARCHAR(64), secret_version VARCHAR(32),
                  secret_hash VARCHAR(128), secret_ciphertext BLOB, status VARCHAR(24),
                  created_at BIGINT, expires_at BIGINT, PRIMARY KEY(app_id, secret_version))
                """);
        repository = new JdbcApplicationRepository(jdbc);
        cipher = new AppSecretCipher("cGhhbnRvbS1kZW1vLW9ubHktMzJieXRlLWtleSEhISE=");
        service = new ApplicationService(repository, cipher, () -> 100L);
    }

    @Test
    void createsApplicationAndKeepsSecretOnlyAsEncryptedMaterial() {
        var created = service.create("Demo");

        assertThat(created.appId()).startsWith("app_");
        assertThat(created.appSecret()).isNotBlank();
        var stored = repository.findSecrets(created.appId());
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().hash()).isNotEqualTo(created.appSecret());
        assertThat(cipher.decrypt(stored.getFirst().ciphertext())).isEqualTo(created.appSecret());
    }

    @Test
    void detailDoesNotExposeSecretPlaintext() {
        var created = service.create("Demo");

        var detail = service.detail(created.appId());

        assertThat(detail.application().appId()).isEqualTo(created.appId());
        assertThat(detail.secrets()).singleElement()
                .satisfies(metadata -> assertThat(metadata.version()).isEqualTo("v1"));
        assertThat(detail.toString()).doesNotContain(created.appSecret());
    }

    @Test
    void rotationKeepsOldSecretInGraceThenExpiresIt() {
        var created = service.create("Demo");
        var rotated = service.rotateSecret(created.appId(), 7);
        var provider = new DatabaseAppSecretProvider(repository, cipher);

        assertThat(provider.usableSecrets(created.appId(), 100L + 1_000L)).hasSize(2);
        assertThat(provider.usableSecrets(created.appId(), rotated.oldSecretExpiresAt() + 1)).hasSize(1);
        assertThat(repository.findSecrets(created.appId()))
                .filteredOn(secret -> secret.version().equals("v1"))
                .singleElement().satisfies(secret -> assertThat(secret.status()).isEqualTo("GRACE"));
    }

    @Test
    void zeroDayRotationRevokesOldSecretImmediately() {
        var created = service.create("Demo");
        var rotated = service.rotateSecret(created.appId(), 0);
        var provider = new DatabaseAppSecretProvider(repository, cipher);

        assertThat(provider.usableSecrets(created.appId(), 100L)).hasSize(1);
        assertThat(repository.findSecrets(created.appId()))
                .filteredOn(secret -> secret.version().equals("v1"))
                .singleElement().satisfies(secret -> assertThat(secret.status()).isEqualTo("REVOKED"));
    }

    @Test
    void updatesAndReadsApplicationFeatureConfig() {
        var created = service.create("Demo");
        var updated = new AppFeatureConfigDto(false, false, true, 30, false, 2, true);

        assertThat(service.updateFeatures(created.appId(), updated)).isEqualTo(updated);
        assertThat(service.features(created.appId())).isEqualTo(updated);
        assertThat(new DatabaseAppConfigProvider(repository).getConfig(created.appId())).isEqualTo(updated);
    }

    @Test
    void oldClientPayloadWithoutRiskFlagDefaultsToDisabled() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        AppFeatureConfigDto config = mapper.readValue("""
                {"relation_hosted":true,"read_receipt_enabled":true,
                 "burn_after_read_enabled":false,"burn_after_read_ttl_seconds":0,
                 "sdk_profile_write_enabled":true,"max_devices":3}
                """, AppFeatureConfigDto.class);

        assertThat(config.riskCheckEnabled()).isFalse();
    }
}
