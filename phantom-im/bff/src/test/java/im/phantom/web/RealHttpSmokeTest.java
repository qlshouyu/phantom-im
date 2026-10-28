package im.phantom.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import im.phantom.web.phantom.PhantomOpenApiClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 冒烟：TestRestTemplate -> 随机端口 BFF -> 真实 MySQL/Redis。
 * Phantom OpenAPI/用户面由测试内 HTTP stub 提供，以便可控验证代理头与上游中断。
 *
 * 运行前置：
 *   docker compose -f deploy/local/docker-compose.yml up -d
 *   cd phantom-im/bff
 *   WEB_IM_REAL_HTTP_SMOKE=true mvn -s .mvn/settings.xml -Dtest=RealHttpSmokeTest test
 */
@EnabledIfEnvironmentVariable(named = "WEB_IM_REAL_HTTP_SMOKE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealHttpSmokeTest {
    private static final String LOGIN_NAME = "smoke_" + (System.currentTimeMillis() % 1_000_000_000L);
    private static final String PASSWORD = "password-smoke-1";
    private static final AtomicReference<String> REQUEST_USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicInteger OPENAPI_CALLS = new AtomicInteger();
    private static final HttpServer UPSTREAM = startUpstream();

    @LocalServerPort
    private int port;

    @Autowired
    private PhantomOpenApiClient upstreamClient;

    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("phantom.web-im.app-id",
                () -> System.getenv().getOrDefault("PHANTOM_WEB_IM_APP_ID", "app_smoke"));
        registry.add("phantom.web-im.app-secret",
                () -> System.getenv().getOrDefault("PHANTOM_WEB_IM_APP_SECRET",
                        "smoke-app-secret-at-least-32-bytes-long"));
        registry.add("phantom.web-im.phantom-http-url",
                () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        registry.add("phantom.web-im.test-mode", () -> "true");
        registry.add("phantom.web-im.session-cookie-max-age", () -> "30d");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("WEB_IM_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/phantom_web_im?useSSL=false&serverTimezone=Asia/Shanghai"
                        + "&characterEncoding=utf8&allowPublicKeyRetrieval=true"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("WEB_IM_DB_USERNAME", "phantom_web_im"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("WEB_IM_DB_PASSWORD", "phantom_web_im_dev"));
        registry.add("spring.data.redis.host",
                () -> System.getenv().getOrDefault("WEB_IM_REDIS_HOST", "127.0.0.1"));
        registry.add("spring.data.redis.port",
                () -> System.getenv().getOrDefault("WEB_IM_REDIS_PORT", "6379"));
        registry.add("spring.data.redis.password",
                () -> System.getenv().getOrDefault("WEB_IM_REDIS_PASSWORD", "123456"));
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Test
    void unauthenticatedRegisterLoginSessionTokenProxyLogoutAndUpstreamFailure() throws Exception {
        HttpResponse<String> stubCheck = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/openapi/users"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(stubCheck.statusCode()).isEqualTo(200);
        assertThat(stubCheck.body()).contains("\"code\":0");
        assertThat(upstreamClient.importUser("direct-client", "Direct Client")).startsWith("u_smoke_");

        HttpResult unauthenticated = request("GET", "/api/auth/me", null, null, null);
        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(unauthenticated.data()).isEmpty();
        assertThat(unauthenticated.body()).containsEntry("code", 2003);

        HttpResult registered = request("POST", "/api/auth/register", """
                {"nickname":"Smoke User","login_name":"%s","password":"%s","password_confirm":"%s"}
                """.formatted(LOGIN_NAME, PASSWORD, PASSWORD), null, null);
        assertThat(registered.status()).withFailMessage("register body=%s openApiCalls=%s",
                registered.body(), OPENAPI_CALLS.get()).isEqualTo(200);
        assertThat(registered.body()).containsEntry("code", 0);
        assertThat(registered.data()).containsKeys("account_id", "phantom_user_id", "status");

        HttpResult login = request("POST", "/api/auth/login", """
                {"login_name":"%s","password":"%s"}
                """.formatted(LOGIN_NAME, PASSWORD), null, null);
        assertThat(login.status()).isEqualTo(200);
        String cookie = login.cookie();
        assertThat(cookie).startsWith("PHANTOM_WEB_IM_SESSION=");
        assertThat(login.setCookie())
                .contains("Max-Age=2592000")
                .contains("Expires=")
                .contains("HttpOnly")
                .contains("SameSite=Lax");

        HttpResult restored = request("GET", "/api/auth/me", null, cookie, null);
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.data()).containsEntry("login_name", LOGIN_NAME);
        assertThat(restored.requestId()).isNotBlank();

        HttpResult token = request("POST", "/api/auth/phantom-token", "", cookie, null);
        assertThat(token.status()).isEqualTo(200);
        assertThat(token.data()).containsKeys("user_token", "phantom_user_id", "app_id", "expires_at");
        String userToken = String.valueOf(token.data().get("user_token"));
        String userId = String.valueOf(token.data().get("phantom_user_id"));

        HttpResult proxied = request("GET", "/api/phantom/users/me", null, cookie,
                Map.of("Authorization", "Bearer " + userToken, "X-User-Id", userId));
        assertThat(proxied.status()).isEqualTo(200);
        assertThat(proxied.data()).containsEntry("user_id", userId);
        assertThat(REQUEST_USER_ID.get()).startsWith("u_smoke_");
        assertThat(AUTHORIZATION.get()).startsWith("Bearer ");

        UPSTREAM.stop(0);
        HttpResult upstreamFailure = request("GET", "/api/phantom/friends", null, cookie,
                Map.of("Authorization", "Bearer " + userToken, "X-User-Id", userId));
        assertThat(upstreamFailure.status()).isEqualTo(502);
        assertThat(upstreamFailure.body()).containsEntry("code", 9001);
        assertThat(upstreamFailure.requestId()).isNotBlank();

        HttpResult logout = request("POST", "/api/auth/logout", "", cookie, null);
        assertThat(logout.status()).isEqualTo(200);
        assertThat(logout.setCookie()).contains("Max-Age=0");
        HttpResult afterLogout = request("GET", "/api/auth/me", null, cookie, null);
        assertThat(afterLogout.status()).isEqualTo(401);
    }

    private HttpResult request(String method, String path, String body, String cookie,
                               Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        if (extraHeaders != null) extraHeaders.forEach(builder::header);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> payload = response.body().isBlank()
                ? Map.of()
                : new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
        String setCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("PHANTOM_WEB_IM_SESSION="))
                .findFirst()
                .orElse("");
        return new HttpResult(response.statusCode(), payload,
                response.headers().firstValue("X-Request-Id").orElse(""),
                setCookie.isEmpty() ? "" : setCookie.split(";", 2)[0], setCookie);
    }

    private record HttpResult(int status, Map<String, Object> body, String requestId,
                              String cookie, String setCookie) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            Object value = body.get("data");
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/openapi/users", exchange -> {
                String userId = "u_smoke_" + System.nanoTime() + "_" + OPENAPI_CALLS.incrementAndGet();
                json(exchange, 200, """
                        {"code":0,"message":"ok","data":{"user_id":"%s","created":true}}
                        """.formatted(userId));
            });
            server.createContext("/users/me", exchange -> {
                String requestUserId = exchange.getRequestHeaders().getFirst("X-User-Id");
                REQUEST_USER_ID.set(requestUserId);
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                json(exchange, 200, """
                        {"code":0,"message":"ok","data":{"user_id":"%s","nickname":"Smoke User"},
                         "request_id":"upstream_req"}
                        """.formatted(requestUserId));
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().flush();
        exchange.close();
    }
}
