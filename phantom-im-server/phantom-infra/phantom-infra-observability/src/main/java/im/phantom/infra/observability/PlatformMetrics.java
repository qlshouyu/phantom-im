package im.phantom.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.function.Supplier;

/** 平台统一指标入口。指标名保持稳定，供告警规则直接引用。 */
public class PlatformMetrics {

    public static final String MQ_CONSUMER_FAILURES = "phantom.mq.consumer.failures";
    public static final String MQ_DLQ_EVENTS = "phantom.mq.dlq.events";
    public static final String MQ_OUTBOX_PENDING = "phantom.mq.outbox.pending";
    public static final String WS_CONNECTIONS_ACTIVE = "phantom.ws.connections.active";
    public static final String PUSH_DELIVERIES = "phantom.push.deliveries";
    public static final String PUSH_DLQ_EVENTS = "phantom.push.dlq.events";

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
}
