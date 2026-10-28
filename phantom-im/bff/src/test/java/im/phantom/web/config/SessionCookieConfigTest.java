package im.phantom.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookieConfigTest {
    @Test
    void writesPersistentHttpOnlySessionCookie() {
        CookieSerializer serializer = new SessionCookieConfig().cookieSerializer(properties(false));
        MockHttpServletResponse response = new MockHttpServletResponse();

        serializer.writeCookieValue(new CookieSerializer.CookieValue(
                new MockHttpServletRequest(), response, "session-id"));

        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("PHANTOM_WEB_IM_SESSION=")
                .contains("Max-Age=2592000")
                .contains("Expires=")
                .contains("Path=/")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure");
    }

    @Test
    void enablesSecureCookieForHttpsDeployments() {
        CookieSerializer serializer = new SessionCookieConfig().cookieSerializer(properties(true));
        MockHttpServletResponse response = new MockHttpServletResponse();

        serializer.writeCookieValue(new CookieSerializer.CookieValue(
                new MockHttpServletRequest(), response, "session-id"));

        assertThat(response.getHeader("Set-Cookie")).contains("Secure");
    }

    @Test
    void serializesCookieDeletionWithImmediateExpiry() {
        CookieSerializer serializer = new SessionCookieConfig().cookieSerializer(properties(false));
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieSerializer.CookieValue value = new CookieSerializer.CookieValue(
                new MockHttpServletRequest(), response, "");
        value.setCookieMaxAge(0);

        serializer.writeCookieValue(value);

        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("PHANTOM_WEB_IM_SESSION=")
                .contains("Max-Age=0")
                .contains("Expires=Thu, 1 Jan 1970");
    }

    private static WebImProperties properties(boolean secureCookie) {
        return new WebImProperties("app_test", "secret",
                "http://localhost:28080", "ws://localhost:28080/ws", "/api/phantom",
                Duration.ofMinutes(15), Duration.ofDays(30), true, secureCookie, List.of());
    }
}
