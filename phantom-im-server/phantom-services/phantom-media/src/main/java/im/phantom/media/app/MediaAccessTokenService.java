package im.phantom.media.app;

import im.phantom.media.MediaProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 绑定 mediaId、userId、appId 和过期时间的短时访问签名。 */
@Component
public class MediaAccessTokenService {

    private final MediaProperties properties;

    public MediaAccessTokenService(MediaProperties properties) {
        this.properties = properties;
    }

    public String sign(String mediaId, String userId, String appId, long expiresAt) {
        return sign(mediaId, "original", userId, appId, expiresAt);
    }

    public String sign(String mediaId, String variant, String userId, String appId, long expiresAt) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(mediaId, variant, userId, appId, expiresAt));
    }

    public boolean verify(String mediaId, String userId, String appId, long expiresAt, String signature) {
        return verify(mediaId, "original", userId, appId, expiresAt, signature);
    }

    public boolean verify(String mediaId, String variant, String userId, String appId, long expiresAt,
                          String signature) {
        if (expiresAt < System.currentTimeMillis() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = hmac(mediaId, variant, userId, appId, expiresAt);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmac(String mediaId, String variant, String userId, String appId, long expiresAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAccessTokenSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return mac.doFinal((mediaId + "\n" + variant + "\n" + userId + "\n" + appId + "\n" + expiresAt)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("媒体访问签名失败", error);
        }
    }
}
