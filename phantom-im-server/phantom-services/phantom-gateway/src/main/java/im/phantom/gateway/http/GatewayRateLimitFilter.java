package im.phantom.gateway.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.common.ErrorCodes;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.HttpAuthAttributes;
import im.phantom.infra.redis.RateLimitDecision;
import im.phantom.infra.redis.RedisRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 关系与群操作的 HTTP 限流，在用户鉴权之后执行。 */
public class GatewayRateLimitFilter extends OncePerRequestFilter {
    private final RedisRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public GatewayRateLimitFilter(RedisRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String action = null;
        long threshold = 0;
        if ("POST".equals(method) && path.equals("/friends/requests")) {
            action = "friend_request"; threshold = 20;
        } else if ("POST".equals(method) && path.equals("/groups")) {
            action = "group_create"; threshold = 5;
        } else if ("POST".equals(method) && path.matches("/groups/[^/]+/members")) {
            action = "group_invite"; threshold = 20;
        }
        if (action != null) {
            String appId = (String) request.getAttribute(HttpAuthAttributes.APP_ID);
            String userId = (String) request.getAttribute(HttpAuthAttributes.USER_ID);
            RateLimitDecision decision = limiter.allow(action, appId + ":" + userId, threshold, 60);
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                response.setContentType("application/json;charset=UTF-8");
                objectMapper.writeValue(response.getWriter(),
                        ApiResponse.error(ErrorCodes.RATE_LIMITED, "操作过于频繁", null));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
