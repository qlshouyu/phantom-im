package im.phantom.gateway.auth;

import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.gateway.GatewayProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * UserToken 验签（协议 6.2）：接入方服务端用 AppSecret 本地签发 JWT(HS256)，平台只验签。
 * 校验：签名有效、未过期、app_id 匹配、user_id 与 claims 一致。M0 单应用。
 */
@Component
public class AuthService {

    private final GatewayProperties props;
    private final SecretKey key;

    public AuthService(GatewayProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getAppSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验 UserToken 并返回其 user_id claim。
     * @throws BizException TOKEN_EXPIRED / AUTH_FAILED
     */
    public String verify(String accessToken, String claimedAppId, String claimedUserId) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken);
            Claims c = jws.getPayload();
            String appId = c.get("app_id", String.class);
            String userId = c.get("user_id", String.class);
            if (appId == null || !appId.equals(props.getAppId()) || !appId.equals(claimedAppId)) {
                throw new BizException(ErrorCodes.AUTH_FAILED, "app_id 不匹配");
            }
            if (userId == null || !userId.equals(claimedUserId)) {
                throw new BizException(ErrorCodes.AUTH_FAILED, "user_id 与 token 不一致");
            }
            return userId;
        } catch (ExpiredJwtException e) {
            throw new BizException(ErrorCodes.TOKEN_EXPIRED, "UserToken 已过期");
        } catch (JwtException e) {
            throw new BizException(ErrorCodes.AUTH_FAILED, "验签失败: " + e.getMessage());
        }
    }
}
