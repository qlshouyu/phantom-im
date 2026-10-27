package im.phantom.push.provider;

import im.phantom.push.spi.PushMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPushProviderTest {

    @Test
    void recordsPushAndReturnsProviderId() {
        LocalPushProvider provider = new LocalPushProvider();
        PushMessage message = new PushMessage("token", "title", "body", "conv", Map.of("conv_id", "c1"));

        assertThat(provider.send(message)).startsWith("push_");
        assertThat(provider.sentMessages()).containsExactly(message);
    }
}
