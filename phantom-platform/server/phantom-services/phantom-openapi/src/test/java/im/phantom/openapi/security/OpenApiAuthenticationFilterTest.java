package im.phantom.openapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.config.AppSecretProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiAuthenticationFilterTest {

    private static final String APP_ID = "app1";
    private static final String SECRET = "01234567890123456789012345678901";
    private static final String PATH = "/openapi/users";
    private static final byte[] BODY = "{\"name\":\"demo\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void validSignaturePassesAndPreservesBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = signedRequest(System.currentTimeMillis(), true);
        AtomicBoolean called = new AtomicBoolean();

        filter(true).doFilter(request, response, (req, res) -> {
            called.set(true);
            assertThat(req.getInputStream().readAllBytes()).isEqualTo(BODY);
        });

        assertThat(called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        MockHttpServletRequest request = signedRequest(System.currentTimeMillis(), false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":2003");
    }

    @Test
    void rejectsExpiredTimestamp() throws Exception {
        MockHttpServletRequest request = signedRequest(System.currentTimeMillis() - 301_000, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("时间戳已过期");
    }

    @Test
    void rejectsRateLimitedApplication() throws Exception {
        MockHttpServletRequest request = signedRequest(System.currentTimeMillis(), true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(false).doFilter(request, response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("\"code\":1003");
    }

    @Test
    void allowsBootstrapApplicationCreationWithBootstrapHmac() throws Exception {
        OpenApiSecurityProperties properties = new OpenApiSecurityProperties();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/openapi/apps");
        long timestamp = System.currentTimeMillis();
        request.addHeader("X-App-Id", properties.getBootstrapAppId());
        request.addHeader("X-Timestamp", String.valueOf(timestamp));
        request.addHeader("X-Signature", hmac(properties.getBootstrapSecret(),
                "POST/openapi/apps" + timestamp));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter(true).doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called).isTrue();
    }

    @Test
    void rejectsReplayOfAlreadyConsumedSignature() throws Exception {
        long timestamp = System.currentTimeMillis();
        OpenApiAuthenticationFilter filter = filter(true);
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(signedRequest(timestamp, true), first, (req, res) -> { });
        MockHttpServletResponse replay = new MockHttpServletResponse();

        filter.doFilter(signedRequest(timestamp, true), replay, (req, res) -> { });

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(replay.getStatus()).isEqualTo(401);
        assertThat(replay.getContentAsString()).contains("请求已重放");
    }

    @Test
    void queryStringParticipatesInHmacCanonicalPath() throws Exception {
        long timestamp = System.currentTimeMillis();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi/risk/reports");
        request.setQueryString("limit=20");
        request.addHeader("X-App-Id", APP_ID);
        request.addHeader("X-Timestamp", String.valueOf(timestamp));
        request.addHeader("X-Signature", hmac(SECRET,
                "GET/openapi/risk/reports?limit=20" + timestamp));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private OpenApiAuthenticationFilter filter(boolean rateAllowed) {
        AppSecretProvider provider = (appId, now) -> appId.equals(APP_ID)
                ? List.of(new AppSecretProvider.SecretMaterial("v1", SECRET, null)) : List.of();
        OpenApiRateLimiter limiter = appId -> rateAllowed;
        OpenApiSecurityProperties properties = new OpenApiSecurityProperties();
        ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
        OpenApiReplayGuard replayGuard = (appId, signature, ttl) -> seen.putIfAbsent(appId + signature, true) == null;
        return new OpenApiAuthenticationFilter(new OpenApiHmacService(provider, properties), limiter, properties,
                replayGuard, new ObjectMapper());
    }

    private MockHttpServletRequest signedRequest(long timestamp, boolean validSignature) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContent(BODY);
        request.addHeader("X-App-Id", APP_ID);
        request.addHeader("X-Timestamp", String.valueOf(timestamp));
        String signature = hmac(SECRET, "POST" + PATH + new String(BODY, StandardCharsets.UTF_8) + timestamp);
        request.addHeader("X-Signature", validSignature ? signature : "00" + signature.substring(2));
        return request;
    }

    private String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
