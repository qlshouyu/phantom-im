package im.phantom.infra.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Redis 基础设施自动装配：取号守卫、路由表实现、ACK 游标。
 * StringRedisTemplate/连接由 Spring Boot 依据 spring.data.redis.* 自动装配。
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = RedisInfraAutoConfiguration.class)
public class RedisInfraAutoConfiguration {
}
