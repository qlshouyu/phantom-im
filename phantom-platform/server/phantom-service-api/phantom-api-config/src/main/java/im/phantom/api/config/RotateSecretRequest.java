package im.phantom.api.config;

/** AppSecret 轮换请求；graceDays 必须为 0-7。 */
public record RotateSecretRequest(int graceDays) {}
