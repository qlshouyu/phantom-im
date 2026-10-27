package im.phantom.risk;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import im.phantom.risk.provider.LocalContentModerationProvider;
import im.phantom.risk.spi.ContentModerationProvider;

/** 风控与合规：审核、举报、处置、申诉和审计。 自动装配入口。 */
@AutoConfiguration
@EnableConfigurationProperties(RiskProperties.class)
@ComponentScan(basePackageClasses = RiskAutoConfiguration.class)
public class RiskAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ContentModerationProvider.class)
    public ContentModerationProvider contentModerationProvider(RiskProperties properties) {
        return new LocalContentModerationProvider(properties.getBlockedKeywords(), properties.isStrictMode());
    }
}
