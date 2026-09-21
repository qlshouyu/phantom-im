package im.phantom.fanout;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** 扇出服务自动装配。 */
@AutoConfiguration
@ComponentScan(basePackageClasses = FanoutAutoConfiguration.class)
public class FanoutAutoConfiguration {
}
