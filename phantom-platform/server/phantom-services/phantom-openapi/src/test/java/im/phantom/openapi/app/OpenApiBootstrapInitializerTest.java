package im.phantom.openapi.app;

import im.phantom.common.time.TimeProvider;
import im.phantom.openapi.security.AppSecretCipher;
import im.phantom.openapi.security.OpenApiSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiBootstrapInitializerTest {

    @Test
    void persistsBootstrapAppConfigAndSecretIdempotently() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OpenApiSecurityProperties properties = new OpenApiSecurityProperties();
        properties.setBootstrapAppId("app_bootstrap");
        properties.setBootstrapSecret("bootstrap-secret-at-least-32-bytes");
        AppSecretCipher cipher = mock(AppSecretCipher.class);
        when(cipher.encrypt(anyString())).thenReturn(new byte[]{1, 2, 3});
        TimeProvider time = () -> 1234L;

        new OpenApiBootstrapInitializer(jdbc, properties, cipher, time).run(null);

        verify(jdbc).update(anyString(), eq("app_bootstrap"), eq("OpenAPI Bootstrap 应用"),
                eq(1234L), eq(1234L));
        verify(jdbc).update(anyString(), eq("app_bootstrap"), eq(true), eq(true), eq(false),
                eq(0), eq(false), eq(true), eq(3), eq(1234L));
        verify(jdbc).update(anyString(), eq("app_bootstrap"),
                eq(im.phantom.openapi.security.AppSecretHasher.sha256(
                        "bootstrap-secret-at-least-32-bytes")),
                eq(new byte[]{1, 2, 3}), eq(1234L));
    }
}
