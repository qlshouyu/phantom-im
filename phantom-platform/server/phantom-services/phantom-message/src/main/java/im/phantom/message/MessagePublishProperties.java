package im.phantom.message;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 消息发布链路配置。 */
@ConfigurationProperties(prefix = "phantom.message")
public class MessagePublishProperties {

    /** transaction-mq（回滚路径）或 outbox（默认推荐）。 */
    private String publishMode = "transaction-mq";
    private long outboxPollMs = 100;
    private int outboxBatchSize = 100;
    private long outboxRetryBaseMs = 100;
    private long outboxRetryMaxMs = 30_000;

    public boolean outboxEnabled() {
        return "outbox".equalsIgnoreCase(publishMode);
    }

    public String getPublishMode() { return publishMode; }
    public void setPublishMode(String publishMode) { this.publishMode = publishMode; }
    public long getOutboxPollMs() { return outboxPollMs; }
    public void setOutboxPollMs(long outboxPollMs) { this.outboxPollMs = outboxPollMs; }
    public int getOutboxBatchSize() { return outboxBatchSize; }
    public void setOutboxBatchSize(int outboxBatchSize) { this.outboxBatchSize = outboxBatchSize; }
    public long getOutboxRetryBaseMs() { return outboxRetryBaseMs; }
    public void setOutboxRetryBaseMs(long outboxRetryBaseMs) { this.outboxRetryBaseMs = outboxRetryBaseMs; }
    public long getOutboxRetryMaxMs() { return outboxRetryMaxMs; }
    public void setOutboxRetryMaxMs(long outboxRetryMaxMs) { this.outboxRetryMaxMs = outboxRetryMaxMs; }
}
