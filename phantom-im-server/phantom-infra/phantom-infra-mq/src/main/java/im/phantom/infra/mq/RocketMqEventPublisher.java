package im.phantom.infra.mq;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;

/** RocketMQ 5.x gRPC 普通消息发布器；首次发布时懒启动，避免无 MQ 场景阻塞应用启动。 */
public class RocketMqEventPublisher implements EventPublisher, AutoCloseable {

    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final MqProperties props;
    private Producer producer;

    public RocketMqEventPublisher(MqProperties props) {
        this.props = props;
    }

    @Override
    public synchronized void publish(String topic, String tag, String key, byte[] body) throws ClientException {
        if (producer == null) {
            ClientConfiguration cc = ClientConfiguration.newBuilder().setEndpoints(props.getEndpoints()).build();
            producer = provider.newProducerBuilder()
                    .setClientConfiguration(cc)
                    .setTopics(Topics.SYNC_EVENT, Topics.PUSH_EVENT, Topics.SEARCH_INDEX_EVENT,
                            Topics.RISK_AUDIT_EVENT, Topics.MEDIA_CLEANUP_EVENT)
                    .build();
        }
        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                .setTag(tag)
                .setKeys(key)
                .setBody(body)
                .build();
        producer.send(message);
    }

    @Override
    public synchronized void close() throws Exception {
        if (producer != null) {
            producer.close();
        }
    }
}
