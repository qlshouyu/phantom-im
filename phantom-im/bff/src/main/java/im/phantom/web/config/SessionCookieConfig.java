package im.phantom.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SessionCookieConfig {
    @Bean
    CookieSerializer cookieSerializer(WebImProperties properties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("PHANTOM_WEB_IM_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(properties.secureCookie());
        long maxAgeSeconds = properties.sessionCookieMaxAge().toSeconds();
        if (maxAgeSeconds <= 0 || maxAgeSeconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("session-cookie-max-age 必须在 1 秒到 Integer.MAX_VALUE 秒之间");
        }
        serializer.setCookieMaxAge((int) maxAgeSeconds);
        return serializer;
    }
}
