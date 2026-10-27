package im.phantom.relation;

import im.phantom.common.time.SystemTimeProvider;
import im.phantom.common.time.TimeProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 关系链：好友申请、好友、分组、黑名单和二维码名片。 自动装配入口。 */
@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackageClasses = RelationAutoConfiguration.class)
public class RelationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TimeProvider.class)
    public TimeProvider relationTimeProvider() {
        return new SystemTimeProvider();
    }
}
