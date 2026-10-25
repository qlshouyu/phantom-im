package im.phantom.web;

import im.phantom.web.config.WebImProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WebImProperties.class)
public class WebImBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebImBffApplication.class, args);
    }
}
