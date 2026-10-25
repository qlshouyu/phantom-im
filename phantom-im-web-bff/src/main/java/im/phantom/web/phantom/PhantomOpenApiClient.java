package im.phantom.web.phantom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.web.common.ApiException;
import im.phantom.web.config.WebImProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PhantomOpenApiClient {
    private static final String IMPORT_USER_PATH = "/openapi/users";
    private final WebImProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PhantomOpenApiClient(WebImProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String importUser(String externalUserId, String nickname) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("external_user_id", externalUserId);
            request.put("nickname", nickname);
            byte[] body = objectMapper.writeValueAsBytes(request);
            long timestamp = System.currentTimeMillis();
            String signature = sign("POST" + IMPORT_USER_PATH
                    + new String(body, StandardCharsets.UTF_8) + timestamp);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(
                            trimTrailingSlash(properties.phantomHttpUrl()) + IMPORT_USER_PATH))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-App-Id", properties.appId())
                    .header("X-Timestamp", Long.toString(timestamp))
                    .header("X-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = objectMapper.readTree(response.body());
            if (response.statusCode() / 100 != 2 || payload.path("code").asInt(-1) != 0) {
                throw new ApiException(9101, HttpStatus.BAD_GATEWAY,
                        "Phantom 用户开通失败：" + payload.path("message").asText("上游服务错误"));
            }
            JsonNode data = payload.path("data");
            String userId = data.path("user_id").asText(data.path("userId").asText(""));
            if (userId.isBlank()) throw new IllegalStateException("Phantom 用户导入响应缺少 user_id");
            return userId;
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(9101, HttpStatus.BAD_GATEWAY, "Phantom 用户开通请求被中断");
        } catch (Exception ex) {
            throw new ApiException(9101, HttpStatus.BAD_GATEWAY, "Phantom 用户开通失败，请稍后重试");
        }
    }

    String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("OpenAPI 签名计算失败", ex);
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
