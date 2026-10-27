package im.phantom.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SameOriginFilterTest {
    private final WebImProperties properties = new WebImProperties("app_test", "secret",
            "http://localhost:28080",
            "ws://localhost:28080/ws", "/api/phantom", Duration.ofMinutes(15),
            true, false, List.of("http://localhost:5173"));
    private final SameOriginFilter filter = new SameOriginFilter(properties,
            new ObjectMapper().findAndRegisterModules());

    @Test
    void rejectsCrossSiteMutationAndFormContent() throws Exception {
        MockHttpServletRequest crossSite = new MockHttpServletRequest("POST", "/api/auth/login");
        crossSite.setContentType("application/json");
        crossSite.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(crossSite, first, new MockFilterChain());
        assertThat(first.getStatus()).isEqualTo(400);

        MockHttpServletRequest form = new MockHttpServletRequest("POST", "/api/auth/login");
        form.setContentType("application/x-www-form-urlencoded");
        form.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(form, second, new MockFilterChain());
        assertThat(second.getStatus()).isEqualTo(400);
    }

    @Test
    void allowsSameOriginJsonMutation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContentType("application/json");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
