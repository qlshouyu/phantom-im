package im.phantom.push.app;

import im.phantom.api.device.PushTokenDto;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class PushSettingsService {
    private final JdbcPushRepository repository;

    public PushSettingsService(JdbcPushRepository repository) { this.repository = repository; }

    @Transactional
    public void registerToken(String userId, PushTokenDto request) {
        if (request == null || request.token() == null || request.token().isBlank()
                || request.token().length() > 1024 || request.provider() == null
                || request.provider().isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "推送 Token 参数非法");
        }
        String hash = sha256(request.token());
        repository.upsertToken(Ids.newId("pt"), userId, request.token(), hash,
                request.deviceType(), request.provider(), System.currentTimeMillis());
    }

    public void unregisterToken(String userId, String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "推送 Token 不能为空");
        }
        repository.deleteToken(userId, sha256(token), System.currentTimeMillis());
    }

    public JdbcPushRepository.Settings getSettings(String userId) { return repository.settings(userId); }

    @Transactional
    public JdbcPushRepository.Settings updateSettings(String userId, boolean pushEnabled,
                                                      boolean previewEnabled) {
        repository.saveSettings(userId, pushEnabled, previewEnabled, System.currentTimeMillis());
        return repository.settings(userId);
    }

    public JdbcPushRepository.Settings privacyLinks(String userId) { return repository.settings(userId); }

    @Transactional
    public JdbcPushRepository.Settings updatePrivacyLinks(String userId, String privacyUrl, String termsUrl) {
        if (privacyUrl != null && privacyUrl.length() > 1024
                || termsUrl != null && termsUrl.length() > 1024) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "隐私链接过长");
        }
        JdbcPushRepository.Settings current = repository.settings(userId);
        repository.savePrivacyLinks(userId, privacyUrl == null ? current.privacyPolicyUrl() : privacyUrl,
                termsUrl == null ? current.termsUrl() : termsUrl, System.currentTimeMillis());
        return repository.settings(userId);
    }

    private String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Token 摘要失败", error);
        }
    }
}
