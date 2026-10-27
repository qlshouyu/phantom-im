package im.phantom.infra.mq;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import im.phantom.proto.v1.SyncEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** RocketMQ 5.x gRPC 普通消息发布器；应用启动时预热生产者，避免首条 Outbox 事件承担冷启动延迟。 */
public class RocketMqEventPublisher implements EventPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RocketMqEventPublisher.class);
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final MqProperties props;
    private Producer producer;

    public RocketMqEventPublisher(MqProperties props) {
        this.props = props;
    }

    public synchronized void start() throws ClientException {
        ensureProducer();
        Message warmup = provider.newMessageBuilder()
                .setTopic(Topics.PRODUCER_WARMUP)
                .setTag(Topics.TAG_NEW_MSG)
                .setKeys("producer-warmup")
                .setBody(new byte[]{0})
                .build();
        producer.send(warmup);
        try {
            Message messageTopicWarmup = provider.newMessageBuilder()
                    .setTopic(Topics.MSG_EVENT)
                    .setTag(Topics.TAG_WARMUP)
                    .setKeys("message-topic-warmup")
                    .setBody(new byte[]{0})
                    .build();
            producer.send(messageTopicWarmup);
        } catch (Exception error) {
            // 预热失败不应阻止启动；真实发送仍会按正常重试语义处理。
            log.warn("TOPIC_MSG_EVENT 生产者预热失败: {}", error.toString());
        }
    }

    @Override
    public synchronized void publish(String topic, String tag, String key, byte[] body) throws ClientException {
        if (Topics.SYNC_EVENT.equals(topic)) {
            validateSyncEnvelope(body);
        }
        ensureProducer();
        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                .setTag(tag)
                .setKeys(key)
                .setBody(body)
                .build();
        producer.send(message);
    }

    private void validateSyncEnvelope(byte[] body) {
        try {
            SyncEventEnvelope.parseFrom(body);
        } catch (Exception error) {
            throw new IllegalArgumentException("同步 Topic payload 不是合法 SyncEventEnvelope", error);
        }
    }

    private void ensureProducer() throws ClientException {
        if (producer == null) {
            ClientConfiguration cc = ClientConfiguration.newBuilder().setEndpoints(props.getEndpoints()).build();
            producer = provider.newProducerBuilder()
                    .setClientConfiguration(cc)
                    .setTopics(Topics.MSG_EVENT, Topics.SYNC_EVENT, Topics.PUSH_EVENT, Topics.SEARCH_INDEX_EVENT,
                            Topics.PRODUCER_WARMUP,
                            Topics.RISK_AUDIT_EVENT, Topics.MEDIA_CLEANUP_EVENT)
                    .build();
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (producer != null) {
            producer.close();
        }
    }
}
