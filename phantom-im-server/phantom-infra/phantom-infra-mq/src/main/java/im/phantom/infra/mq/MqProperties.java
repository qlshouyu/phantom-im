package im.phantom.infra.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MQ 配置（RocketMQ 5.x gRPC 客户端，连接 proxy）。 */
@ConfigurationProperties(prefix = "phantom.mq")
public class MqProperties {
    /** RocketMQ proxy gRPC 端点（5.x 客户端经 proxy 收发，proxy 内部路由 broker）。 */
    private String endpoints = "127.0.0.1:8081";
    /** 生产者/事务组。 */
    private String producerGroup = "phantom-tx-producer";

    public String getEndpoints() { return endpoints; }
    public void setEndpoints(String endpoints) { this.endpoints = endpoints; }
    public String getProducerGroup() { return producerGroup; }
    public void setProducerGroup(String producerGroup) { this.producerGroup = producerGroup; }
}
