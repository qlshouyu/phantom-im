package im.phantom.web.phantom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyPathPolicyTest {
    private final ProxyPathPolicy policy = new ProxyPathPolicy();

    @Test
    void allowsOnlyUserFacingRoots() {
        assertThat(policy.isAllowed("/conversations?limit=50")).isTrue();
        assertThat(policy.isAllowed("/media/upload/init")).isTrue();
        assertThat(policy.isAllowed("/search/suggest")).isTrue();
        assertThat(policy.isAllowed("/openapi/users")).isFalse();
        assertThat(policy.isAllowed("/internal/users")).isFalse();
        assertThat(policy.isAllowed("//evil.example/users")).isFalse();
        assertThat(policy.isAllowed("/users/../openapi/users")).isFalse();
    }
}
