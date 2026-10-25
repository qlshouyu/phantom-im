package im.phantom.web.phantom;

import im.phantom.web.auth.SessionAccount;
import im.phantom.web.auth.SessionSupport;
import im.phantom.web.common.ApiException;
import im.phantom.web.common.ApiResponse;
import im.phantom.web.common.RequestIdFilter;
import im.phantom.web.config.WebImProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@RestController
public class ProxyController {
    private static final Set<String> FORWARD_HEADERS = Set.of(
            "authorization", "accept", "content-type", "user-agent", "x-device-id");

    private final ProxyPathPolicy pathPolicy;
    private final SessionSupport sessions;
    private final WebImProperties properties;
    private final HttpClient httpClient;

    public ProxyController(ProxyPathPolicy pathPolicy, SessionSupport sessions, WebImProperties properties) {
        this.pathPolicy = pathPolicy;
        this.sessions = sessions;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @RequestMapping("/api/phantom/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        SessionAccount account = sessions.require(request);
        String requestUserId = request.getHeader("X-User-Id");
        if (requestUserId == null || !requestUserId.equals(account.phantomUserId())) {
            throw new ApiException(2003, HttpStatus.FORBIDDEN, "用户身份与当前会话不一致");
        }
        String path = request.getRequestURI().substring("/api/phantom".length());
        if (!pathPolicy.isAllowed(path)) {
            throw new ApiException(1001, HttpStatus.FORBIDDEN, "代理路径不允许访问");
        }
        String query = request.getQueryString();
        URI upstream = URI.create(trimTrailingSlash(properties.phantomHttpUrl()) + path
                + (query == null ? "" : "?" + query));
        try {
            HttpRequest.BodyPublisher bodyPublisher = request.getContentLengthLong() == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(request.getInputStream().readAllBytes());
            HttpRequest.Builder builder = HttpRequest.newBuilder(upstream)
                    .timeout(Duration.ofSeconds(20))
                    .method(request.getMethod(), bodyPublisher);
            request.getHeaderNames().asIterator().forEachRemaining(name -> {
                if (FORWARD_HEADERS.contains(name.toLowerCase())) {
                    request.getHeaders(name).asIterator().forEachRemaining(value ->
                            builder.header(name, value));
                }
            });
            builder.header("X-App-Id", properties.appId());
            builder.header("X-User-Id", account.phantomUserId());
            HttpResponse<byte[]> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            ResponseEntity.BodyBuilder result = ResponseEntity.status(response.statusCode());
            if (contentType != null) {
                result.contentType(mediaType(contentType));
            }
            String requestId = response.headers().firstValue("X-Request-Id")
                    .orElse(String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
            result.header("X-Request-Id", requestId);
            return result.body(response.statusCode() == 204 ? null : response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw upstreamError(9003, "Phantom 请求被中断");
        } catch (ApiException ex) {
            throw ex;
        } catch (java.net.http.HttpTimeoutException ex) {
            throw upstreamError(9003, "Phantom 服务响应超时");
        } catch (Exception ex) {
            throw upstreamError(9001, "Phantom 服务暂不可用");
        }
    }

    private static ApiException upstreamError(int code, String message) {
        return new ApiException(code, HttpStatus.BAD_GATEWAY, message);
    }

    private static MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
