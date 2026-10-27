package im.phantom.infra.mq;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import im.phantom.infra.observability.PlatformMetrics;

/**
 * MQ 基础设施装配：MqProperties + 消费者工厂。
 * 事务生产者(TxMqProducer)由持有 TransactionChecker 的业务 module（message）自行装配，
 * 避免条件装配时序问题，也符合「各 module 装配自己的出口适配」。
 */
@AutoConfiguration
@EnableConfigurationProperties(MqProperties.class)
public class MqInfraAutoConfiguration {

    @Bean(destroyMethod = "close")
    public MqConsumers mqConsumers(MqProperties props, PlatformMetrics metrics) {
        return new MqConsumers(props, metrics);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public EventPublisher eventPublisher(MqProperties props) {
        return new RocketMqEventPublisher(props);
    }
}
