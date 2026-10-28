package im.phantom.web.auth;

import im.phantom.web.config.WebImProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private static final String APP_SECRET = "test-app-secret-at-least-32-bytes-long";
    private final WebImProperties properties = new WebImProperties("app_test", APP_SECRET,
            "http://localhost:28080", "ws://localhost:28080/ws", "/api/phantom",
            Duration.ofMinutes(15), Duration.ofDays(30), true, false,
            List.of("http://localhost:5173"));
    private final JwtService service = new JwtService(properties);
    private final SessionAccount account = new SessionAccount("account-1", "user-1", "alice", "Alice", 1L);

    @Test
    void issuesSignedHs256TokenWithRequiredClaims() {
        PhantomTokenResponse response = service.issue(account);
        SecretKey key = Keys.hmacShaKeyFor(APP_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).clockSkewSeconds(120).build().parseSignedClaims(response.userToken()).getPayload();
        assertThat(claims.get("app_id", String.class)).isEqualTo("app_test");
        assertThat(claims.get("user_id", String.class)).isEqualTo("user-1");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        assertThat(response.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void expiredTokenIsAlreadyExpired() {
        PhantomTokenResponse response = service.issueExpired(account);
        SecretKey key = Keys.hmacShaKeyFor(APP_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).clockSkewSeconds(120).build().parseSignedClaims(response.userToken()).getPayload();
        assertThat(claims.getExpiration()).isBefore(claims.getIssuedAt());
    }
}
