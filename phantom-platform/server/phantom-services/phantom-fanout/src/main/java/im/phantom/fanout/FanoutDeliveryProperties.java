package im.phantom.fanout;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** fanout 在线投递队列配置。 */
@ConfigurationProperties(prefix = "phantom.fanout.delivery")
public class FanoutDeliveryProperties {

    private int workerThreads = 8;
    private int queueCapacity = 5_000;
    private int perUserCapacity = 50;
    private long shutdownTimeoutMs = 10_000;

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getPerUserCapacity() { return perUserCapacity; }
    public void setPerUserCapacity(int perUserCapacity) { this.perUserCapacity = perUserCapacity; }
    public long getShutdownTimeoutMs() { return shutdownTimeoutMs; }
    public void setShutdownTimeoutMs(long shutdownTimeoutMs) { this.shutdownTimeoutMs = shutdownTimeoutMs; }
}
