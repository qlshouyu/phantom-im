package im.phantom.media;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import im.phantom.media.spi.MediaStorage;
import im.phantom.media.storage.LocalMediaStorage;

/** 媒体：分片上传、对象元数据、访问控制和生命周期清理。 自动装配入口。 */
@AutoConfiguration
@EnableConfigurationProperties(MediaProperties.class)
@ComponentScan(basePackageClasses = MediaAutoConfiguration.class)
public class MediaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MediaStorage.class)
    public MediaStorage mediaStorage(MediaProperties properties) {
        return new LocalMediaStorage(properties.getLocalRoot());
    }
}
