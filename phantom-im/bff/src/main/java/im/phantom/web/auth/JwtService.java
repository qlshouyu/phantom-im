package im.phantom.web.auth;

import im.phantom.web.config.WebImProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final WebImProperties properties;
    private final SecretKey key;

    public JwtService(WebImProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.appSecret().getBytes(StandardCharsets.UTF_8));
    }

    public PhantomTokenResponse issue(SessionAccount account) {
        return issue(account, properties.tokenTtl().toSeconds());
    }

    public PhantomTokenResponse issueExpired(SessionAccount account) {
        return issue(account, -60);
    }

    private PhantomTokenResponse issue(SessionAccount account, long ttlSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String token = Jwts.builder()
                .claim("app_id", properties.appId())
                .claim("user_id", account.phantomUserId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new PhantomTokenResponse(token, account.phantomUserId(), properties.appId(),
                expiresAt.toEpochMilli(), ttlSeconds);
    }
}
