package im.phantom.openapi.app;

import im.phantom.api.config.AppSecretProvider;
import im.phantom.openapi.security.AppSecretCipher;
import org.springframework.stereotype.Component;

import java.util.List;

/** 从密钥版本表读取并解密用于验签的可用密钥。 */
@Component
public class DatabaseAppSecretProvider implements AppSecretProvider {

    private final ApplicationRepository repository;
    private final AppSecretCipher cipher;

    public DatabaseAppSecretProvider(ApplicationRepository repository, AppSecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Override
    public List<SecretMaterial> usableSecrets(String appId, long nowMillis) {
        return repository.findUsableSecrets(appId, nowMillis).stream()
                .map(secret -> new SecretMaterial(secret.version(), cipher.decrypt(secret.ciphertext()), secret.expiresAt()))
                .toList();
    }
}
