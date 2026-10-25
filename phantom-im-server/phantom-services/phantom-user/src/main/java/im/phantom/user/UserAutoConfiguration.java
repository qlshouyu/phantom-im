package im.phantom.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;

import im.phantom.common.time.SystemTimeProvider;
import im.phantom.common.time.TimeProvider;

/** 用户身份：Phantom ID、资料和用户状态。 自动装配入口。 */
@AutoConfiguration
@ComponentScan(basePackageClasses = UserAutoConfiguration.class)
public class UserAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TimeProvider.class)
    public TimeProvider userTimeProvider() {
        return new SystemTimeProvider();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper userObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
