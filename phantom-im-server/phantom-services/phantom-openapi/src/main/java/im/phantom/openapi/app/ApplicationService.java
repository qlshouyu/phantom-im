package im.phantom.openapi.app;

import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.api.config.AppSecretMetadataDto;
import im.phantom.api.config.ApplicationDetailDto;
import im.phantom.api.config.ApplicationDto;
import im.phantom.api.config.CreateApplicationResponse;
import im.phantom.api.config.RotateSecretResponse;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.common.time.TimeProvider;
import im.phantom.openapi.security.AppSecretCipher;
import im.phantom.openapi.security.AppSecretHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/** 应用创建与详情用例。 */
@Service
public class ApplicationService {

    private final ApplicationRepository repository;
    private final AppSecretCipher cipher;
    private final TimeProvider time;
    private final SecureRandom random = new SecureRandom();

    public ApplicationService(ApplicationRepository repository, AppSecretCipher cipher, TimeProvider time) {
        this.repository = repository;
        this.cipher = cipher;
        this.time = time;
    }

    @Transactional
    public CreateApplicationResponse create(String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "应用名称长度必须为 1-128");
        }
        long now = time.nowMillis();
        String appId = Ids.newAppId();
        String secret = newSecret();
        AppFeatureConfigDto features = defaultFeatures();
        ApplicationRepository.ApplicationRecord app = new ApplicationRepository.ApplicationRecord(
                appId, name.trim(), "ACTIVE", now, now, features);
        ApplicationRepository.SecretRecord secretRecord = new ApplicationRepository.SecretRecord(
                appId, "v1", AppSecretHasher.sha256(secret), cipher.encrypt(secret), "ACTIVE", now, null);
        repository.create(app, features, secretRecord);
        return new CreateApplicationResponse(appId, secret, features);
    }

    @Transactional(readOnly = true)
    public ApplicationDetailDto detail(String appId) {
        ApplicationRepository.ApplicationRecord app = repository.findById(appId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "应用不存在"));
        var secrets = repository.findSecrets(appId).stream()
                .map(s -> new AppSecretMetadataDto(s.version(), s.createdAt(), s.expiresAt(), s.status()))
                .toList();
        return new ApplicationDetailDto(new ApplicationDto(app.appId(), app.name(), app.status(),
                app.createdAt(), app.features()), secrets);
    }

    @Transactional
    public RotateSecretResponse rotateSecret(String appId, int graceDays) {
        if (graceDays < 0 || graceDays > 7) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "graceDays 必须为 0-7");
        }
        repository.findById(appId).orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "应用不存在"));
        long now = time.nowMillis();
        Long oldExpiresAt = graceDays == 0 ? now : now + graceDays * 24L * 60L * 60L * 1000L;
        for (ApplicationRepository.SecretRecord old : repository.findSecrets(appId)) {
            if ("ACTIVE".equals(old.status()) || "GRACE".equals(old.status())) {
                repository.updateSecretStatus(appId, old.version(), graceDays == 0 ? "REVOKED" : "GRACE", oldExpiresAt);
            }
        }
        String secret = newSecret();
        String version = Ids.newId("v");
        if (version.length() > 32) {
            version = version.substring(0, 32);
        }
        repository.insertSecret(new ApplicationRepository.SecretRecord(
                appId, version, AppSecretHasher.sha256(secret), cipher.encrypt(secret), "ACTIVE", now, null));
        return new RotateSecretResponse(appId, version, secret, oldExpiresAt);
    }

    @Transactional(readOnly = true)
    public AppFeatureConfigDto features(String appId) {
        return repository.findById(appId)
                .map(ApplicationRepository.ApplicationRecord::features)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "应用不存在"));
    }

    @Transactional
    public AppFeatureConfigDto updateFeatures(String appId, AppFeatureConfigDto features) {
        if (features == null || features.maxDevices() < 1) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "设备上限必须大于 0");
        }
        if (features.burnAfterReadEnabled() && features.burnAfterReadTtlSeconds() <= 0) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "开启阅后即焚时 TTL 必须大于 0");
        }
        repository.findById(appId).orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "应用不存在"));
        repository.updateFeatures(appId, features, time.nowMillis());
        return features;
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AppFeatureConfigDto defaultFeatures() {
        return new AppFeatureConfigDto(true, true, false, 0, true, 3);
    }
}
