package im.phantom.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Phantom IM 单体启动器（可分可合的「合」形态）。
 * 各业务 module 经 Spring Boot 自动装配（AutoConfiguration.imports）自暴露，
 * 本类不做全局 @ComponentScan，避免单体形态下的隐式耦合（工程结构设计 §2）。
 */
@SpringBootApplication
@EnableTransactionManagement
public class PhantomMonolithApplication {
    public static void main(String[] args) {
        SpringApplication.run(PhantomMonolithApplication.class, args);
    }
}
