package im.phantom.push;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** 推送服务自动装配。 */
@AutoConfiguration
@ComponentScan(basePackageClasses = PushAutoConfiguration.class)
public class PushAutoConfiguration {
}
