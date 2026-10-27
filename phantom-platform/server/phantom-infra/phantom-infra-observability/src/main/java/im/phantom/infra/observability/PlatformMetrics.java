package im.phantom.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 平台统一指标入口。指标名保持稳定，供告警规则直接引用。 */
public class PlatformMetrics {

    public static final String MQ_CONSUMER_FAILURES = "phantom.mq.consumer.failures";
    public static final String MQ_DLQ_EVENTS = "phantom.mq.dlq.events";
    public static final String MQ_OUTBOX_PENDING = "phantom.mq.outbox.pending";
    public static final String MQ_OUTBOX_OLDEST_AGE = "phantom.mq.outbox.oldest.age";
    public static final String MQ_OUTBOX_RETRIES = "phantom.mq.outbox.retries";
    public static final String WS_CONNECTIONS_ACTIVE = "phantom.ws.connections.active";
    public static final String PUSH_DELIVERIES = "phantom.push.deliveries";
    public static final String PUSH_DLQ_EVENTS = "phantom.push.dlq.events";
    public static final String MESSAGE_STAGE_DURATION = "phantom.message.stage.duration";
    public static final String MESSAGE_STAGE_SKIPPED = "phantom.message.stage.skipped";
    public static final String WS_SEND_TIMEOUTS = "phantom.ws.send.timeouts";
    public static final String DELIVERY_QUEUE_DEPTH = "phantom.delivery.queue.depth";
    public static final String DELIVERY_QUEUE_REJECTED = "phantom.delivery.queue.rejected";

    private final MeterRegistry registry;

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordConsumerFailure(String group, String topic) {
        Counter.builder(MQ_CONSUMER_FAILURES)
                .tag("group", group)
                .tag("topic", topic)
                .register(registry)
                .increment();
    }

    public void recordDlqEvent(String group, String topic) {
        Counter.builder(MQ_DLQ_EVENTS)
                .tag("group", group)
                .tag("topic", topic)
                .register(registry)
                .increment();
    }

    public void registerOutboxPending(Supplier<Number> supplier) {
        Gauge.builder(MQ_OUTBOX_PENDING, supplier, value -> value.get().doubleValue())
                .register(registry);
    }

    public void registerOutboxOldestAge(Supplier<Number> supplier) {
        Gauge.builder(MQ_OUTBOX_OLDEST_AGE, supplier, value -> value.get().doubleValue())
                .description("最老待发布 Outbox 事件年龄（毫秒）")
                .register(registry);
    }

    public void recordOutboxRetry() {
        Counter.builder(MQ_OUTBOX_RETRIES)
                .description("Outbox 发布重试次数")
                .register(registry)
                .increment();
    }

    public void registerActiveConnections(Supplier<Number> supplier) {
        Gauge.builder(WS_CONNECTIONS_ACTIVE, supplier, value -> value.get().doubleValue())
                .register(registry);
    }

    public void recordPushDelivery(String provider, String result) {
        Counter.builder(PUSH_DELIVERIES)
                .tag("provider", provider)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void recordPushDlq(String provider) {
        Counter.builder(PUSH_DLQ_EVENTS)
                .tag("provider", provider)
                .register(registry)
                .increment();
    }

    /** 开始一个消息链路阶段计时；调用方必须保证最终 stop 或 record 一次。 */
    public Timer.Sample startMessageStage() {
        return Timer.start(registry);
    }

    public void recordMessageStage(Timer.Sample sample, String stage, String result) {
        sample.stop(Timer.builder(MESSAGE_STAGE_DURATION)
                .description("消息链路各阶段耗时")
                .tag("stage", stage)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry));
    }

    public void recordMessageStage(String stage, String result, long durationNanos) {
        Timer.builder(MESSAGE_STAGE_DURATION)
                .description("消息链路各阶段耗时")
                .tag("stage", stage)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordMessageStageSkipped(String stage, String reason) {
        Counter.builder(MESSAGE_STAGE_SKIPPED)
                .description("消息链路被跳过阶段计数")
                .tag("stage", stage)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordWebSocketPush(String deviceType, String result, long durationNanos) {
        Timer.builder(MESSAGE_STAGE_DURATION)
                .description("消息链路各阶段耗时")
                .tag("stage", "ws_push")
                .tag("result", result)
                .tag("device_type", deviceType)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordWebSocketSendTimeout(String deviceType) {
        Counter.builder(WS_SEND_TIMEOUTS)
                .description("WebSocket 单设备发送超时次数")
                .tag("device_type", deviceType)
                .register(registry)
                .increment();
    }

    public void registerDeliveryQueueDepth(String queue, Supplier<Number> supplier) {
        Gauge.builder(DELIVERY_QUEUE_DEPTH, supplier, value -> value.get().doubleValue())
                .description("有界投递队列积压深度")
                .tag("queue", queue)
                .register(registry);
    }

    public void recordDeliveryQueueRejected(String queue, String reason) {
        Counter.builder(DELIVERY_QUEUE_REJECTED)
                .description("有界投递队列拒绝次数")
                .tag("queue", queue)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
