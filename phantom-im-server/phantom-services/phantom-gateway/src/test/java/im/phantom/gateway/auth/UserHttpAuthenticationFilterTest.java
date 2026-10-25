package im.phantom.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.http.HttpAuthAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserHttpAuthenticationFilterTest {

    @Test
    void missingTokenIsRejectedBeforeControllerRuns() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserHttpAuthenticationFilter filter = new UserHttpAuthenticationFilter(authService, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/friends");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":2003");
    }

    @Test
    void validUserTokenPopulatesTrustedRequestContext() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserHttpAuthenticationFilter filter = new UserHttpAuthenticationFilter(authService, new ObjectMapper());
        MockHttpServletRequest request = authenticatedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> {
            called.set(true);
            assertThat(req.getAttribute(HttpAuthAttributes.APP_ID)).isEqualTo("app1");
            assertThat(req.getAttribute(HttpAuthAttributes.USER_ID)).isEqualTo("u1");
        });

        assertThat(called).isTrue();
        verify(authService).verify("token-value", "app1", "u1");
    }

    @Test
    void bannedUserGetsForbiddenResponse() throws Exception {
        AuthService authService = mock(AuthService.class);
        doThrow(new BizException(ErrorCodes.ACCOUNT_BANNED, "用户已被封禁"))
                .when(authService).verify("token-value", "app1", "u1");
        UserHttpAuthenticationFilter filter = new UserHttpAuthenticationFilter(authService, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(authenticatedRequest(), response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":6002");
    }

    @Test
    void searchSuggestionPathReceivesAuthenticatedUserContext() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserHttpAuthenticationFilter filter = new UserHttpAuthenticationFilter(authService, new ObjectMapper());
        MockHttpServletRequest request = authenticatedRequest("/search/suggest");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> {
            called.set(true);
            assertThat(req.getAttribute(HttpAuthAttributes.USER_ID)).isEqualTo("u1");
        });

        assertThat(called).isTrue();
        verify(authService).verify("token-value", "app1", "u1");
    }

    private MockHttpServletRequest authenticatedRequest() {
        return authenticatedRequest("/friends");
    }

    private MockHttpServletRequest authenticatedRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer token-value");
        request.addHeader("X-App-Id", "app1");
        request.addHeader("X-User-Id", "u1");
        return request;
    }
}
