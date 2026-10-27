package im.phantom.gateway.auth;

import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.proto.v1.AuthResp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AuthResponseFactoryTest {

    @Test
    void mapsApplicationConfigIntoAuthResponse() {
        AppFeatureConfigDto config = new AppFeatureConfigDto(false, false, true, 45, false, 2);
        AppConfigProvider provider = appId -> config;
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("appConfigProvider", provider);
        AuthResponseFactory responseFactory = new AuthResponseFactory(factory.getBeanProvider(AppConfigProvider.class));

        AuthResp response = responseFactory.build("app1", 100L, 20L, 270);

        assertThat(response.getServerTime()).isEqualTo(100L);
        assertThat(response.getMaxUserSeq()).isEqualTo(20L);
        assertThat(response.getAppFeatures().getRelationHosted()).isFalse();
        assertThat(response.getAppFeatures().getSdkProfileWriteEnabled()).isFalse();
        assertThat(response.getAppFeatures().getMaxDevices()).isEqualTo(2);
        assertThat(response.getReadReceipt().getEnabled()).isFalse();
        assertThat(response.getBurnAfterRead().getEnabled()).isTrue();
        assertThat(response.getBurnAfterRead().getTtlSeconds()).isEqualTo(45);
    }

    @Test
    void fallsBackToDefaultsWhenProviderIsMissing() {
        AuthResponseFactory responseFactory = new AuthResponseFactory(
                new DefaultListableBeanFactory().getBeanProvider(AppConfigProvider.class));

        AuthResp response = responseFactory.build("app1", 1L, 0L, 270);

        assertThat(response.getAppFeatures().getRelationHosted()).isTrue();
        assertThat(response.getAppFeatures().getMaxDevices()).isEqualTo(3);
    }
}
