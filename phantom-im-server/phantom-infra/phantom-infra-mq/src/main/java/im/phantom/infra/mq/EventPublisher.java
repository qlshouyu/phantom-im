package im.phantom.infra.mq;

/** 领域 outbox 使用的非事务消息发布契约。 */
public interface EventPublisher {

    void publish(String topic, String tag, String key, byte[] body) throws Exception;
}
