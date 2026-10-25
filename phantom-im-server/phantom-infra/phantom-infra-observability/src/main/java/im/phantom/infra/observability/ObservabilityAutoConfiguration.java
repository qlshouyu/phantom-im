package im.phantom.infra.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 可观测性基础设施自动装配。 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PlatformMetrics platformMetrics(MeterRegistry registry) {
        return new PlatformMetrics(registry);
    }
}
