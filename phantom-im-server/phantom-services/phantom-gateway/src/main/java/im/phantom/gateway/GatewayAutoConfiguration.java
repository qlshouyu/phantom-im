package im.phantom.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.route.RouteTableService;
import im.phantom.api.route.UserSeqQuery;
import im.phantom.api.config.AppConfigProvider;
import im.phantom.common.gateway.CommandHandler;
import im.phantom.common.gateway.MessageAckHandler;
import im.phantom.gateway.auth.AuthService;
import im.phantom.gateway.auth.AuthResponseFactory;
import im.phantom.gateway.auth.UserHttpAuthenticationFilter;
import im.phantom.gateway.ws.CommandDispatcher;
import im.phantom.gateway.ws.ConnectionRegistry;
import im.phantom.gateway.ws.PhantomWsHandler;
import im.phantom.gateway.ws.PresenceRegistry;
import im.phantom.gateway.device.DeviceRegistryService;
import im.phantom.gateway.http.GatewayRateLimitFilter;
import im.phantom.infra.redis.AckCursorStore;
import im.phantom.infra.redis.RedisRateLimiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 网关服务自动装配。 */
@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
@ComponentScan(basePackageClasses = GatewayAutoConfiguration.class)
public class GatewayAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService gatewayScheduler() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "gw-sched");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public PhantomWsHandler phantomWsHandler(AuthService authService, RouteTableService routeTable,
                                             UserSeqQuery userSeqQuery, ConnectionRegistry registry,
                                             CommandDispatcher dispatcher, AckCursorStore ackCursor,
                                             GatewayProperties props,
                                             @Qualifier("gatewayScheduler") ScheduledExecutorService gatewayScheduler,
                                             AuthResponseFactory authResponseFactory,
                                             ObjectProvider<MessageAckHandler> ackHandlers,
                                             PresenceRegistry presenceRegistry,
                                             ObjectProvider<AppConfigProvider> appConfigs,
                                             DeviceRegistryService deviceRegistry,
                                             RedisRateLimiter rateLimiter) {
        return new PhantomWsHandler(authService, routeTable, userSeqQuery, registry, dispatcher,
                ackCursor, props, gatewayScheduler, authResponseFactory, ackHandlers, presenceRegistry,
                appConfigs, deviceRegistry, rateLimiter);
    }

    @Bean
    public AuthResponseFactory authResponseFactory(ObjectProvider<AppConfigProvider> configProvider) {
        return new AuthResponseFactory(configProvider);
    }

    @Bean
    public CommandDispatcher commandDispatcher(List<CommandHandler> handlers) {
        return new CommandDispatcher(handlers);
    }

    @Bean
    public FilterRegistrationBean<UserHttpAuthenticationFilter> userHttpAuthenticationFilter(
            AuthService authService, ObjectMapper objectMapper) {
        FilterRegistrationBean<UserHttpAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new UserHttpAuthenticationFilter(authService, objectMapper));
        registration.addUrlPatterns("/users/*", "/friends/*", "/blacklist/*", "/conversations/*",
                "/messages/*", "/groups", "/groups/*", "/media/*", "/devices/*", "/push/*", "/search",
                "/search/*", "/reports", "/appeals", "/account/*");
        registration.setOrder(20);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<GatewayRateLimitFilter> gatewayRateLimitFilter(
            RedisRateLimiter limiter, ObjectMapper objectMapper) {
        FilterRegistrationBean<GatewayRateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new GatewayRateLimitFilter(limiter, objectMapper));
        registration.addUrlPatterns("/friends/requests", "/groups", "/groups/*");
        registration.setOrder(30);
        return registration;
    }
}
