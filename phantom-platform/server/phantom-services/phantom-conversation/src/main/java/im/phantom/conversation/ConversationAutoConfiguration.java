package im.phantom.conversation;

import im.phantom.common.time.SystemTimeProvider;
import im.phantom.common.time.TimeProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 会话状态：会话快照、未读、已读位置、置顶、免打扰和草稿。 自动装配入口。 */
@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackageClasses = ConversationAutoConfiguration.class)
public class ConversationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TimeProvider.class)
    public TimeProvider conversationTimeProvider() {
        return new SystemTimeProvider();
    }
}
