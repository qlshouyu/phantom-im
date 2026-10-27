package im.phantom.infra.mq;

/**
 * 中立消息处理契约：让业务 module 不直接依赖 RocketMQ API。
 * 返回 true = 消费成功；false/抛异常 = 稍后重试（重试耗尽进 DLQ，不丢）。
 */
@FunctionalInterface
public interface MqMessageHandler {
    boolean handle(byte[] body) throws Exception;
}
