package im.phantom.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** JJWT/过滤器兼容 Jackson 2；MVC 默认 Jackson 3 由 Boot 自动配置。 */
@Configuration
public class JacksonCompatConfig {
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
