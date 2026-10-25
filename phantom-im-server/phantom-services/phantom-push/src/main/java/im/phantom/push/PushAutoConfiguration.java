package im.phantom.push;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import im.phantom.push.provider.LocalPushProvider;
import im.phantom.push.spi.PushProvider;

/** 推送服务自动装配。 */
@AutoConfiguration
@ComponentScan(basePackageClasses = PushAutoConfiguration.class)
public class PushAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @Qualifier("pushScheduler")
    public ScheduledExecutorService pushScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "push-ack-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean(PushProvider.class)
    public PushProvider pushProvider() {
        return new LocalPushProvider();
    }
}
