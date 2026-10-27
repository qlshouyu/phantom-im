package im.phantom.group;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 群组：成员、角色、邀请、禁言、公告和群生命周期。 自动装配入口。 */
@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackageClasses = GroupAutoConfiguration.class)
public class GroupAutoConfiguration {
}
