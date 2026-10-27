package im.phantom.web.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "phantom.web-im")
public record WebImProperties(
        @NotBlank String appId,
        @NotBlank String appSecret,
        @NotBlank String phantomHttpUrl,
        @NotBlank String phantomWsUrl,
        @NotBlank String proxyBaseUrl,
        @NotNull Duration tokenTtl,
        boolean testMode,
        boolean secureCookie,
        List<String> allowedOrigins) {
    public WebImProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
