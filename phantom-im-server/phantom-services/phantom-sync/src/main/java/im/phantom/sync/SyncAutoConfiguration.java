package im.phantom.sync;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** 同步服务自动装配。 */
@AutoConfiguration
@ComponentScan(basePackageClasses = SyncAutoConfiguration.class)
public class SyncAutoConfiguration {
}
