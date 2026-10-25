package im.phantom.push.provider;

import im.phantom.push.spi.PushDeliveryException;
import im.phantom.push.spi.PushMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushProviderContractTest {

    @Test
    void apnsAndFcmUseUnifiedPayloadAndClassifyInvalidTokens() {
        PushMessage message = new PushMessage("device-token", "title", "body",
                "conv:c1", Map.of("conv_id", "c1", "deep_link", "phantom://conversation/c1"));

        assertThat(new ApnsPushProvider("im.phantom.app").send(message)).startsWith("apns_");
        assertThat(new FcmPushProvider("phantom-project").send(message)).startsWith("fcm_");
        assertThatThrownBy(() -> new ApnsPushProvider("im.phantom.app")
                .send(new PushMessage("", "title", "body", "conv:c1", Map.of())))
                .isInstanceOf(PushDeliveryException.class)
                .extracting(error -> ((PushDeliveryException) error).invalidToken()).isEqualTo(true);
        assertThatThrownBy(() -> new FcmPushProvider("phantom-project")
                .send(new PushMessage("token", null, "body", "conv:c1", Map.of())))
                .isInstanceOf(PushDeliveryException.class)
                .extracting(error -> ((PushDeliveryException) error).retryable()).isEqualTo(true);
    }
}
