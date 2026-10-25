package im.phantom.common;

import im.phantom.common.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonTypesTest {

    @Test
    void generatedIdsCarryDomainPrefixAndAreUnique() {
        String first = Ids.newUserId();
        String second = Ids.newUserId();

        assertThat(first).startsWith("u_");
        assertThat(first).isNotEqualTo(second);
        assertThat(Ids.newMsgId()).startsWith("m_");
        assertThat(Ids.newGroupId()).startsWith("g_");
        assertThat(Ids.newMediaId()).startsWith("media_");
    }

    @Test
    void singleAndGroupConversationIdsAreStable() {
        assertThat(ConvIds.single("u2", "u1")).isEqualTo(ConvIds.single("u1", "u2"));
        assertThat(ConvIds.group("g1")).isEqualTo("gr_g1");
        assertThat(ConvIds.isGroup("gr_g1")).isTrue();
        assertThat(ConvIds.isSingle(ConvIds.single("u1", "u2"))).isTrue();
    }

    @Test
    void pageRequestAppliesDefaultsAndUpperBound() {
        assertThat(new PageRequest(null, 0).limit()).isEqualTo(PageRequest.DEFAULT_LIMIT);
        assertThat(new PageRequest("c", 9_999).limit()).isEqualTo(PageRequest.MAX_LIMIT);
        assertThat(PageRequest.firstPage().first()).isTrue();
    }

    @Test
    void pageResultIsImmutable() {
        PageResult<String> result = new PageResult<>(List.of("a"), "next", true);

        assertThatThrownBy(() -> result.items().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void appContextRejectsBlankFields() {
        assertThat(new AppContext("app_1", "u_1").appId()).isEqualTo("app_1");
        assertThatThrownBy(() -> new AppContext(" ", "u_1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeProviderUsesInjectedClock() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_234L), ZoneOffset.UTC);

        assertThat(new SystemTimeProvider(clock).nowMillis()).isEqualTo(1_234L);
    }
}
