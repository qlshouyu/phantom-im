package im.phantom.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.web.common.ApiResponse;
import im.phantom.web.common.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SameOriginFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final WebImProperties properties;
    private final ObjectMapper objectMapper;

    public SameOriginFilter(WebImProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null && (contentType.contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                || contentType.contains(MediaType.MULTIPART_FORM_DATA_VALUE))) {
            reject(response, request, 1001, "请求必须使用 JSON 或二进制格式");
            return;
        }
        String source = request.getHeader("Origin");
        if (source == null || source.isBlank()) source = request.getHeader("Referer");
        if (source != null && !source.isBlank() && !isAllowedOrigin(source)) {
            reject(response, request, 1001, "跨站请求被拒绝");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowedOrigin(String source) {
        try {
            URI uri = URI.create(source);
            String origin = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            return properties.allowedOrigins().contains(origin);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void reject(HttpServletResponse response, HttpServletRequest request, int code, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message,
                requestId == null ? null : requestId.toString()));
    }
}
