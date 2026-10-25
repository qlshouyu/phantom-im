package im.phantom.risk.provider;

import im.phantom.risk.spi.ModerationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalContentModerationProviderTest {

    @Test
    void blocksConfiguredKeyword() {
        LocalContentModerationProvider provider = new LocalContentModerationProvider(List.of("违禁词"), false);

        assertThat(provider.moderate(new ModerationRequest("a", "u", "TEXT", "包含违禁词", null, null)).allowed())
                .isFalse();
    }

    @Test
    void allowsNormalTextAndReviewsImageInStrictMode() {
        LocalContentModerationProvider provider = new LocalContentModerationProvider(List.of(), true);

        assertThat(provider.moderate(new ModerationRequest("a", "u", "TEXT", "正常内容", null, null)).allowed())
                .isTrue();
        assertThat(provider.moderate(new ModerationRequest("a", "u", "IMAGE", null, "m1", "image/png")).reviewRequired())
                .isTrue();
    }
}
