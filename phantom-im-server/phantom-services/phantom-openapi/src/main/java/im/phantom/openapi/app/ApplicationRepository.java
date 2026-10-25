package im.phantom.openapi.app;

import im.phantom.api.config.AppFeatureConfigDto;

import java.util.List;
import java.util.Optional;

/** 应用与密钥版本持久化契约。 */
public interface ApplicationRepository {

    void create(ApplicationRecord application, AppFeatureConfigDto config, SecretRecord secret);

    Optional<ApplicationRecord> findById(String appId);

    List<SecretRecord> findSecrets(String appId);

    void insertSecret(SecretRecord secret);

    void updateSecretStatus(String appId, String version, String status, Long expiresAt);

    List<SecretRecord> findUsableSecrets(String appId, long nowMillis);

    void updateFeatures(String appId, AppFeatureConfigDto features, long updatedAt);

    record ApplicationRecord(String appId, String name, String status, long createdAt, long updatedAt,
                             AppFeatureConfigDto features) {}

    record SecretRecord(String appId, String version, String hash, byte[] ciphertext, String status,
                        long createdAt, Long expiresAt) {}
}
