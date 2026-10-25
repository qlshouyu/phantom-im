package im.phantom.infra.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformMetricsTest {

    @Test
    void recordsStableMqMetricNamesAndTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformMetrics metrics = new PlatformMetrics(registry);

        metrics.recordConsumerFailure("fanout", "TOPIC_MSG_EVENT");
        metrics.recordDlqEvent("fanout", "TOPIC_MSG_EVENT");

        assertThat(registry.get(PlatformMetrics.MQ_CONSUMER_FAILURES)
                .tag("group", "fanout").tag("topic", "TOPIC_MSG_EVENT").counter().count()).isEqualTo(1);
        assertThat(registry.get(PlatformMetrics.MQ_DLQ_EVENTS).counter().count()).isEqualTo(1);
    }

    @Test
    void traceContextPopulatesMdc() {
        try {
            assertThat(TraceContext.start("req_1", "trace_1")).isEqualTo("req_1");
            assertThat(org.slf4j.MDC.get(TraceContext.TRACE_ID)).isEqualTo("trace_1");
        } finally {
            TraceContext.clear();
        }
    }
}
