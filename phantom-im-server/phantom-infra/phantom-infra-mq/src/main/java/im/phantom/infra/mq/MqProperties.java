package im.phantom.infra.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** MQ 配置（RocketMQ 5.x gRPC 客户端，连接 proxy）。 */
@ConfigurationProperties(prefix = "phantom.mq")
public class MqProperties {
    /** RocketMQ proxy gRPC 端点（5.x 客户端经 proxy 收发，proxy 内部路由 broker）。 */
    private String endpoints = "127.0.0.1:8081";
    /** 生产者/事务组。 */
    private String producerGroup = "phantom-tx-producer";
    /** 默认 PushConsumer 并发与缓存上限。 */
    private int consumptionThreadCount = 20;
    private int maxCacheMessageCount = 1024;
    private int maxCacheMessageSizeBytes = 64 * 1024 * 1024;
    /** 按消费域覆盖并发与缓存上限，例如 fanout / conversation / search。 */
    private Map<String, ConsumerSettings> consumers = new LinkedHashMap<>();

    public String getEndpoints() { return endpoints; }
    public void setEndpoints(String endpoints) { this.endpoints = endpoints; }
    public String getProducerGroup() { return producerGroup; }
    public void setProducerGroup(String producerGroup) { this.producerGroup = producerGroup; }
    public int getConsumptionThreadCount() { return consumptionThreadCount; }
    public void setConsumptionThreadCount(int consumptionThreadCount) { this.consumptionThreadCount = consumptionThreadCount; }
    public int getMaxCacheMessageCount() { return maxCacheMessageCount; }
    public void setMaxCacheMessageCount(int maxCacheMessageCount) { this.maxCacheMessageCount = maxCacheMessageCount; }
    public int getMaxCacheMessageSizeBytes() { return maxCacheMessageSizeBytes; }
    public void setMaxCacheMessageSizeBytes(int maxCacheMessageSizeBytes) {
        this.maxCacheMessageSizeBytes = maxCacheMessageSizeBytes;
    }
    public Map<String, ConsumerSettings> getConsumers() { return consumers; }
    public void setConsumers(Map<String, ConsumerSettings> consumers) {
        this.consumers = consumers == null ? new LinkedHashMap<>() : consumers;
    }

    public ConsumerSettings consumer(String profile) {
        ConsumerSettings configured = consumers.get(profile);
        return configured == null ? new ConsumerSettings(consumptionThreadCount, maxCacheMessageCount,
                maxCacheMessageSizeBytes) : configured;
    }

    public static class ConsumerSettings {
        private int consumptionThreadCount;
        private int maxCacheMessageCount;
        private int maxCacheMessageSizeBytes;

        public ConsumerSettings() { }

        public ConsumerSettings(int consumptionThreadCount, int maxCacheMessageCount,
                                int maxCacheMessageSizeBytes) {
            this.consumptionThreadCount = consumptionThreadCount;
            this.maxCacheMessageCount = maxCacheMessageCount;
            this.maxCacheMessageSizeBytes = maxCacheMessageSizeBytes;
        }

        public int getConsumptionThreadCount() { return consumptionThreadCount; }
        public void setConsumptionThreadCount(int consumptionThreadCount) {
            this.consumptionThreadCount = consumptionThreadCount;
        }
        public int getMaxCacheMessageCount() { return maxCacheMessageCount; }
        public void setMaxCacheMessageCount(int maxCacheMessageCount) {
            this.maxCacheMessageCount = maxCacheMessageCount;
        }
        public int getMaxCacheMessageSizeBytes() { return maxCacheMessageSizeBytes; }
        public void setMaxCacheMessageSizeBytes(int maxCacheMessageSizeBytes) {
            this.maxCacheMessageSizeBytes = maxCacheMessageSizeBytes;
        }
    }
}
