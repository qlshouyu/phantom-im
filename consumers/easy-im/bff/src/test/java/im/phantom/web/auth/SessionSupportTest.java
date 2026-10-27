package im.phantom.web.auth;

import im.phantom.web.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionSupportTest {
    private final SessionSupport support = new SessionSupport();

    @Test
    void readsIdentityFromSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        var session = request.getSession(true);
        session.setAttribute(SessionKeys.ACCOUNT_ID, "account-1");
        session.setAttribute(SessionKeys.PHANTOM_USER_ID, "user-1");
        session.setAttribute(SessionKeys.LOGIN_NAME, "alice");
        session.setAttribute(SessionKeys.NICKNAME, "Alice");
        session.setAttribute(SessionKeys.AUTHENTICATED_AT, 42L);
        assertThat(support.require(request).phantomUserId()).isEqualTo("user-1");
    }

    @Test
    void sessionStoreFailureNeverFallsBackToAnonymous() {
        MockHttpServletRequest request = mock(MockHttpServletRequest.class);
        when(request.getSession(false)).thenThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(() -> support.require(request))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .hasToString("503 SERVICE_UNAVAILABLE");
    }

    @Test
    void missingSessionFailsClosed() {
        assertThatThrownBy(() -> support.require(new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .hasToString("401 UNAUTHORIZED");
    }
}
