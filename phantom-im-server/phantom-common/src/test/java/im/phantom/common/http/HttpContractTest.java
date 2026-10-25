package im.phantom.common.http;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..");

    @Test
    void everyRouteConstantAppearsInRouteDocument() throws Exception {
        String routes = Files.readString(REPO_ROOT.resolve("docs/api/mvp-routes.md"));

        for (Field field : ApiRoutes.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                assertThat(routes).contains((String) field.get(null));
            }
        }
    }

    @Test
    void openApiUsesCanonicalWireFieldNames() throws Exception {
        String openapi = Files.readString(REPO_ROOT.resolve("docs/api/mvp-openapi.yaml"));

        assertThat(openapi).contains(
                "msg_id:", "client_msg_id:", "conv_id:", "conv_seq:", "user_seq:",
                "server_time:", "is_request:", "relation_hosted:", "max_devices:",
                "MSG_STATUS_DELIVERED"
        );
    }

    @Test
    void unifiedResponseContainsRequestIdForTracing() {
        ApiResponse<String> response = ApiResponse.ok("value", "req_1");

        assertThat(response.code()).isZero();
        assertThat(response.requestId()).isEqualTo("req_1");
        assertThat(List.of(response.data())).containsExactly("value");
    }
}
