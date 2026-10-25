package im.phantom.infra.mq;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import im.phantom.infra.observability.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 消费者工厂（RocketMQ 5.x gRPC PushConsumer）：按需创建并启动，容器关闭时统一 close。
 * M0 并发消费；顺序消费（ShardingKey=conv_id）留待后续（design D4：conv_seq 已保证可观测有序）。
 */
public class MqConsumers implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MqConsumers.class);

    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final MqProperties props;
    private final PlatformMetrics metrics;
    private final List<PushConsumer> started = new ArrayList<>();

    public MqConsumers(MqProperties props, PlatformMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    public synchronized void newConsumer(String group, String topic, String tag,
                                         MqMessageHandler handler) throws ClientException {
        ClientConfiguration cc = ClientConfiguration.newBuilder()
                .setEndpoints(props.getEndpoints())
                .build();
        FilterExpression filter = new FilterExpression(tag, FilterExpressionType.TAG);
        PushConsumer consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(cc)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Collections.singletonMap(topic, filter))
                .setMessageListener(view -> {
                    try {
                        ByteBuffer bb = view.getBody();
                        byte[] body = new byte[bb.remaining()];
                        bb.get(body);
                        boolean success = handler.handle(body);
                        if (!success) {
                            metrics.recordConsumerFailure(group, topic);
                        }
                        return success ? ConsumeResult.SUCCESS : ConsumeResult.FAILURE;
                    } catch (Exception e) {
                        metrics.recordConsumerFailure(group, topic);
                        log.warn("消费失败，稍后重试 group={}: {}", group, e.toString());
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        started.add(consumer);
        log.info("MQ 消费者启动 group={} topic={} tag={}", group, topic, tag);
    }

    @Override
    public synchronized void close() {
        for (PushConsumer c : started) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("消费者关闭异常: {}", e.toString());
            }
        }
    }
}
