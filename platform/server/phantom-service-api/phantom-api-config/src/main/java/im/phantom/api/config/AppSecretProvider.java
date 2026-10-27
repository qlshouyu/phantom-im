package im.phantom.api.config;

import java.util.List;

/** 验签可用密钥查询契约；只返回 ACTIVE 或仍在宽限期的 GRACE 密钥。 */
public interface AppSecretProvider {

    List<SecretMaterial> usableSecrets(String appId, long nowMillis);

    record SecretMaterial(String version, String secret, Long expiresAt) {}
}
