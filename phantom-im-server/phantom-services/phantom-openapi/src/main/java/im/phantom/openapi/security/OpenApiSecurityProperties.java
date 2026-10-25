package im.phantom.openapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenAPI 安全参数。 */
@ConfigurationProperties(prefix = "phantom.openapi.security")
public class OpenApiSecurityProperties {

    private long timestampSkewSeconds = 300;
    private long rateLimitPerMinute = 60;
    private String bootstrapAppId = "console";
    private String bootstrapSecret = "phantom-console-dev-secret-change-me";

    public long getTimestampSkewSeconds() { return timestampSkewSeconds; }
    public void setTimestampSkewSeconds(long timestampSkewSeconds) { this.timestampSkewSeconds = timestampSkewSeconds; }
    public long getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(long rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public String getBootstrapAppId() { return bootstrapAppId; }
    public void setBootstrapAppId(String bootstrapAppId) { this.bootstrapAppId = bootstrapAppId; }
    public String getBootstrapSecret() { return bootstrapSecret; }
    public void setBootstrapSecret(String bootstrapSecret) { this.bootstrapSecret = bootstrapSecret; }
}
