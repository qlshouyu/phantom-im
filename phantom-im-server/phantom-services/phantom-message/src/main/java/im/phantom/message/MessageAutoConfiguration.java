package im.phantom.message;

import im.phantom.infra.mq.MqProperties;
import im.phantom.infra.mq.TransactionChecker;
import im.phantom.infra.mq.TxMqProducer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * 消息服务自动装配。launcher 引入依赖即生效（无需全局 @ComponentScan）。
 * 事务生产者在此装配（本 module 持有 TransactionChecker）。
 */
@AutoConfiguration
@EnableConfigurationProperties(MessagePublishProperties.class)
@ComponentScan(basePackageClasses = MessageAutoConfiguration.class)
public class MessageAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public TxMqProducer txMqProducer(MqProperties props, TransactionChecker checker) throws Exception {
        return new TxMqProducer(props, checker);
    }
}
