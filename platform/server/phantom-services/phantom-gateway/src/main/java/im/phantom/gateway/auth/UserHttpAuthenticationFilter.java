package im.phantom.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.HttpAuthAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** SDK 端 HTTP 鉴权：Authorization: Bearer UserToken + X-App-Id + X-User-Id。 */
public class UserHttpAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public UserHttpAuthenticationFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/users") || path.startsWith("/users/")
                || path.equals("/friends") || path.startsWith("/friends/")
                || path.equals("/blacklist") || path.startsWith("/blacklist/")
                || path.equals("/conversations") || path.startsWith("/conversations/")
                || path.equals("/messages") || path.startsWith("/messages/")
                || path.equals("/groups") || path.startsWith("/groups/")
                || path.equals("/media") || path.startsWith("/media/")
                || path.equals("/devices") || path.startsWith("/devices/")
                || path.equals("/push") || path.startsWith("/push/")
                || path.equals("/search") || path.startsWith("/search/")
                || path.equals("/reports") || path.startsWith("/reports/")
                || path.equals("/appeals") || path.startsWith("/appeals/")
                || path.equals("/account") || path.startsWith("/account/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String appId = request.getHeader("X-App-Id");
        String userId = request.getHeader("X-User-Id");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
                || appId == null || appId.isBlank() || userId == null || userId.isBlank()) {
            writeError(response, ErrorCodes.AUTH_FAILED, "缺少用户鉴权信息", 401);
            return;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            authService.verify(token, appId, userId);
        } catch (BizException error) {
            int status = error.code() == ErrorCodes.ACCOUNT_BANNED ? 403 : 401;
            writeError(response, error.code(), error.getMessage(), status);
            return;
        }
        request.setAttribute(HttpAuthAttributes.APP_ID, appId);
        request.setAttribute(HttpAuthAttributes.USER_ID, userId);
        request.setAttribute(HttpAuthAttributes.ACCESS_TOKEN, token);
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int code, String message, int status) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, message, null)));
    }
}
