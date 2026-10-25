package im.phantom.gateway.auth;

import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.api.config.AppSecretProvider;
import im.phantom.api.config.AppStatusProvider;
import im.phantom.api.user.UserService;
import im.phantom.api.user.UserStatus;
import im.phantom.gateway.GatewayProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * UserToken 验签（协议 6.2）：接入方服务端用 AppSecret 本地签发 JWT(HS256)，平台只验签。
 * 校验：签名有效、未过期、app_id 匹配、user_id 与 claims 一致。M0 单应用。
 */
@Component
public class AuthService {

    private final GatewayProperties props;
    private final ObjectProvider<AppSecretProvider> secretProvider;
    private final ObjectProvider<UserService> userService;
    private final ObjectProvider<AppStatusProvider> appStatusProvider;
    private final SecretKey fallbackKey;

    public AuthService(GatewayProperties props, ObjectProvider<AppSecretProvider> secretProvider,
                       ObjectProvider<UserService> userService,
                       ObjectProvider<AppStatusProvider> appStatusProvider) {
        this.props = props;
        this.secretProvider = secretProvider;
        this.userService = userService;
        this.appStatusProvider = appStatusProvider;
        this.fallbackKey = Keys.hmacShaKeyFor(props.getAppSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验 UserToken 并返回其 user_id claim。
     * @throws BizException TOKEN_EXPIRED / AUTH_FAILED
     */
    public String verify(String accessToken, String claimedAppId, String claimedUserId) {
        AppSecretProvider provider = secretProvider.getIfAvailable();
        List<AppSecretProvider.SecretMaterial> secrets = provider == null
                ? List.of(new AppSecretProvider.SecretMaterial("fallback", props.getAppSecret(), null))
                : provider.usableSecrets(claimedAppId, System.currentTimeMillis());
        if (secrets.isEmpty() && props.getAppId().equals(claimedAppId)) {
            secrets = List.of(new AppSecretProvider.SecretMaterial("fallback", props.getAppSecret(), null));
        }
        boolean expired = false;
        for (AppSecretProvider.SecretMaterial material : secrets) {
            try {
                SecretKey key = Keys.hmacShaKeyFor(material.secret().getBytes(StandardCharsets.UTF_8));
                Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken);
                Claims c = jws.getPayload();
                String appId = c.get("app_id", String.class);
                String userId = c.get("user_id", String.class);
                if (!claimedAppId.equals(appId) || (provider == null && !props.getAppId().equals(appId))) {
                    continue;
                }
                if (userId == null || !userId.equals(claimedUserId)) {
                    continue;
                }
                AppStatusProvider apps = appStatusProvider.getIfAvailable();
                if (apps != null && !apps.isActive(claimedAppId)) {
                    throw new BizException(ErrorCodes.AUTH_FAILED, "应用不可用");
                }
                UserService users = userService.getIfAvailable();
                if (users != null) {
                    var status = users.findStatus(claimedAppId, userId);
                    if (status.isEmpty()) {
                        throw new BizException(ErrorCodes.AUTH_FAILED, "用户不属于当前应用");
                    }
                    if (status.get() == UserStatus.DISABLED || status.get() == UserStatus.DELETED) {
                        throw new BizException(ErrorCodes.TOKEN_REVOKED, "用户状态不可用");
                    }
                    if (status.get() == UserStatus.BANNED) {
                        throw new BizException(ErrorCodes.ACCOUNT_BANNED, "用户已被封禁");
                    }
                }
                return userId;
            } catch (ExpiredJwtException e) {
                expired = true;
            } catch (JwtException | IllegalArgumentException e) {
                // 尝试下一版本密钥；全部失败后统一返回验签失败。
            }
        }
        if (expired) {
            throw new BizException(ErrorCodes.TOKEN_EXPIRED, "UserToken 已过期");
        }
        throw new BizException(ErrorCodes.AUTH_FAILED, "验签失败");
    }
}
