package im.phantom.gateway;

import im.phantom.api.route.RouteTableService;
import im.phantom.api.route.UserSeqQuery;
import im.phantom.common.gateway.CommandHandler;
import im.phantom.gateway.auth.AuthService;
import im.phantom.gateway.ws.CommandDispatcher;
import im.phantom.gateway.ws.ConnectionRegistry;
import im.phantom.gateway.ws.PhantomWsHandler;
import im.phantom.infra.redis.AckCursorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

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
                                             GatewayProperties props, ScheduledExecutorService gatewayScheduler) {
        return new PhantomWsHandler(authService, routeTable, userSeqQuery, registry, dispatcher,
                ackCursor, props, gatewayScheduler);
    }

    @Bean
    public CommandDispatcher commandDispatcher(List<CommandHandler> handlers) {
        return new CommandDispatcher(handlers);
    }
}
