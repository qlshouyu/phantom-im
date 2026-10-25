package im.phantom.openapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.common.ErrorCodes;
import im.phantom.common.http.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** `/openapi/*` HMAC-SHA256 鉴权、时间窗校验与应用维度限流。 */
public class OpenApiAuthenticationFilter extends OncePerRequestFilter {

    public static final String APP_ID_ATTRIBUTE = "phantom.appId";

    private final OpenApiHmacService hmacService;
    private final OpenApiRateLimiter rateLimiter;
    private final OpenApiSecurityProperties properties;
    private final OpenApiReplayGuard replayGuard;
    private final ObjectMapper objectMapper;

    public OpenApiAuthenticationFilter(OpenApiHmacService hmacService, OpenApiRateLimiter rateLimiter,
                                       OpenApiSecurityProperties properties, OpenApiReplayGuard replayGuard,
                                       ObjectMapper objectMapper) {
        this.hmacService = hmacService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.replayGuard = replayGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/openapi/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String appId = request.getHeader("X-App-Id");
        String timestampValue = request.getHeader("X-Timestamp");
        String signature = request.getHeader("X-Signature");
        if (appId == null || appId.isBlank() || timestampValue == null || signature == null) {
            reject(response, 401, ErrorCodes.AUTH_FAILED, "缺少 OpenAPI 鉴权头");
            return;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampValue);
        } catch (NumberFormatException e) {
            reject(response, 401, ErrorCodes.AUTH_FAILED, "X-Timestamp 非法");
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > properties.getTimestampSkewSeconds() * 1000L) {
            reject(response, 401, ErrorCodes.AUTH_FAILED, "请求时间戳已过期");
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        String canonicalPath = request.getQueryString() == null
                ? request.getRequestURI() : request.getRequestURI() + "?" + request.getQueryString();
        if (!hmacService.verify(appId, request.getMethod(), timestamp, canonicalPath,
                body, signature, now)) {
            reject(response, 401, ErrorCodes.AUTH_FAILED, "OpenAPI 签名校验失败");
            return;
        }
        if (!replayGuard.claim(appId, signature, properties.getTimestampSkewSeconds() * 2L)) {
            reject(response, 401, ErrorCodes.AUTH_FAILED, "OpenAPI 请求已重放");
            return;
        }
        if (!rateLimiter.allow(appId)) {
            reject(response, 429, ErrorCodes.RATE_LIMITED, "OpenAPI 请求过于频繁");
            return;
        }
        request.setAttribute(APP_ID_ATTRIBUTE, appId);
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private void reject(HttpServletResponse response, int httpStatus, int code, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message, null));
    }
}
