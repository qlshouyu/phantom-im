package im.phantom.fanout;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import im.phantom.fanout.app.FanoutDeliveryExecutor;
import im.phantom.infra.observability.PlatformMetrics;

/** 扇出服务自动装配。 */
@AutoConfiguration
@EnableConfigurationProperties(FanoutDeliveryProperties.class)
@ComponentScan(basePackageClasses = FanoutAutoConfiguration.class)
public class FanoutAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    public FanoutDeliveryExecutor fanoutDeliveryExecutor(FanoutDeliveryProperties properties,
                                                         PlatformMetrics metrics) {
        return new FanoutDeliveryExecutor(properties, metrics);
    }
}
