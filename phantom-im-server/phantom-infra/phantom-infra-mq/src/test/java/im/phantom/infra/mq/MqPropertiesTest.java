package im.phantom.infra.mq;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MqPropertiesTest {

    @Test
    void resolvesIndependentConsumerProfilesWithDefaultFallback() {
        MqProperties properties = new MqProperties();
        properties.setConsumers(Map.of(
                "fanout", new MqProperties.ConsumerSettings(8, 512, 32 * 1024 * 1024),
                "search", new MqProperties.ConsumerSettings(4, 256, 16 * 1024 * 1024)));

        assertThat(properties.consumer("fanout").getConsumptionThreadCount()).isEqualTo(8);
        assertThat(properties.consumer("fanout").getMaxCacheMessageCount()).isEqualTo(512);
        assertThat(properties.consumer("search").getConsumptionThreadCount()).isEqualTo(4);
        assertThat(properties.consumer("unknown").getConsumptionThreadCount()).isEqualTo(20);
        assertThat(properties.consumer("unknown").getMaxCacheMessageCount()).isEqualTo(1024);
    }
}
