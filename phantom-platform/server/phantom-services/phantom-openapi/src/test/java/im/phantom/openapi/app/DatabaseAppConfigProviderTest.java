package im.phantom.openapi.app;

import im.phantom.api.config.AppFeatureConfigDto;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseAppConfigProviderTest {

    @Test
    void cachesConfigForConfiguredTtlAndReloadsAfterExpiry() {
        ApplicationRepository repository = mock(ApplicationRepository.class);
        AppFeatureConfigDto disabled = new AppFeatureConfigDto(true, true, false, 0, true, 3, false);
        AppFeatureConfigDto enabled = new AppFeatureConfigDto(true, true, false, 0, true, 3, true);
        when(repository.findById("app"))
                .thenReturn(Optional.of(record(disabled)))
                .thenReturn(Optional.of(record(enabled)));
        AtomicLong clock = new AtomicLong();
        DatabaseAppConfigProvider provider = new DatabaseAppConfigProvider(repository, 2_000L, clock::get);

        assertThat(provider.getConfig("app")).isEqualTo(disabled);
        clock.set(1_999_999_999L);
        assertThat(provider.getConfig("app")).isEqualTo(disabled);
        verify(repository).findById("app");

        clock.set(2_000_000_000L);
        assertThat(provider.getConfig("app")).isEqualTo(enabled);
        verify(repository, org.mockito.Mockito.times(2)).findById("app");
    }

    private ApplicationRepository.ApplicationRecord record(AppFeatureConfigDto config) {
        return new ApplicationRepository.ApplicationRecord("app", "App", "ACTIVE", 1L, 2L, config);
    }
}
