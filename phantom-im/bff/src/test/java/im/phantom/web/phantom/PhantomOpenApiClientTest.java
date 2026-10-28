package im.phantom.web.phantom;

import im.phantom.web.config.WebImProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PhantomOpenApiClientTest {
    @Test
    void signsCanonicalMethodPathBodyAndTimestamp() {
        WebImProperties properties = new WebImProperties("app_test", "secret",
                "http://localhost:28080",
                "ws://localhost:28080/ws", "/api/phantom", Duration.ofMinutes(15),
                Duration.ofDays(30), true, false, List.of());
        PhantomOpenApiClient client = new PhantomOpenApiClient(properties,
                new com.fasterxml.jackson.databind.ObjectMapper());
        String signature = client.sign("POST/openapi/users{\"external_user_id\":\"a\"}123");
        assertThat(signature).hasSize(64).matches("[0-9a-f]+");
    }
}
