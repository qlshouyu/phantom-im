package im.phantom.openapi.security;

import im.phantom.api.config.AppSecretProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;

/** OpenAPI HMAC-SHA256 签名校验。 */
public class OpenApiHmacService {

    private final AppSecretProvider secretProvider;
    private final OpenApiSecurityProperties properties;

    public OpenApiHmacService(AppSecretProvider secretProvider, OpenApiSecurityProperties properties) {
        this.secretProvider = secretProvider;
        this.properties = properties;
    }

    public boolean verify(String appId, String method, long timestamp, String path,
                          byte[] body, String signature, long nowMillis) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        List<AppSecretProvider.SecretMaterial> materials = new ArrayList<>(
                secretProvider.usableSecrets(appId, nowMillis));
        if (properties.getBootstrapAppId().equals(appId)) {
            materials.add(new AppSecretProvider.SecretMaterial(
                    "bootstrap", properties.getBootstrapSecret(), null));
        }
        return materials.stream().anyMatch(material -> {
            String canonical = method + path + new String(body, StandardCharsets.UTF_8) + timestamp;
            String expected = hmac(material.secret(), canonical);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        });
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("OpenAPI 签名计算失败", e);
        }
    }
}
