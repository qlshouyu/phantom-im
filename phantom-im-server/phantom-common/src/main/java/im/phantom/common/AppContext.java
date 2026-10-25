package im.phantom.common;

import java.util.Objects;

/**
 * 一次可信业务调用中的应用上下文。
 *
 * <p>由认证过滤器、OpenAPI 鉴权或内部消息消费者构造，业务层不得从未校验的请求体自行补齐。
 */
public record AppContext(String appId, String userId) {

    public AppContext {
        appId = requireNonBlank(appId, "appId");
        userId = requireNonBlank(userId, "userId");
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
