package im.phantom.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import im.phantom.api.config.AppSecretProvider;
import im.phantom.common.time.SystemTimeProvider;
import im.phantom.common.time.TimeProvider;
import im.phantom.infra.redis.RedisRateLimiter;
import im.phantom.openapi.security.AppSecretCipher;
import im.phantom.openapi.security.OpenApiAuthenticationFilter;
import im.phantom.openapi.security.OpenApiHmacService;
import im.phantom.openapi.security.OpenApiRateLimiter;
import im.phantom.openapi.security.OpenApiReplayGuard;
import im.phantom.openapi.security.RedisOpenApiReplayGuard;
import im.phantom.openapi.security.OpenApiSecurityProperties;
import im.phantom.openapi.security.RedisOpenApiRateLimiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.redis.core.StringRedisTemplate;

/** OpenAPI 管理面：应用、密钥、应用配置与用户管理。 自动装配入口。 */
@AutoConfiguration
@EnableConfigurationProperties({OpenApiProperties.class, OpenApiSecurityProperties.class})
@ComponentScan(basePackageClasses = OpenApiAutoConfiguration.class)
public class OpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TimeProvider.class)
    public TimeProvider timeProvider() {
        return new SystemTimeProvider();
    }

    @Bean
    public AppSecretCipher appSecretCipher(OpenApiProperties properties) {
        return new AppSecretCipher(properties.getSecretKey());
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Bean
    @ConditionalOnMissingBean(OpenApiRateLimiter.class)
    public OpenApiRateLimiter openApiRateLimiter(RedisRateLimiter limiter, OpenApiSecurityProperties properties) {
        return new RedisOpenApiRateLimiter(limiter, properties.getRateLimitPerMinute());
    }

    @Bean
    public OpenApiHmacService openApiHmacService(AppSecretProvider secretProvider, OpenApiSecurityProperties properties) {
        return new OpenApiHmacService(secretProvider, properties);
    }

    @Bean
    @ConditionalOnMissingBean(OpenApiReplayGuard.class)
    public OpenApiReplayGuard openApiReplayGuard(StringRedisTemplate redis) {
        return new RedisOpenApiReplayGuard(redis);
    }

    @Bean
    public FilterRegistrationBean<OpenApiAuthenticationFilter> openApiAuthenticationFilter(
            OpenApiHmacService hmacService, OpenApiRateLimiter rateLimiter,
            OpenApiSecurityProperties properties, OpenApiReplayGuard replayGuard, ObjectMapper objectMapper) {
        FilterRegistrationBean<OpenApiAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OpenApiAuthenticationFilter(hmacService, rateLimiter, properties,
                replayGuard, objectMapper));
        registration.addUrlPatterns("/openapi/*");
        registration.setOrder(10);
        return registration;
    }
}
