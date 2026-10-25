package im.phantom.infra.db;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * DB 基础设施装配标记。DataSource 与 JdbcTemplate 由 Spring Boot 依据 spring.datasource.*
 * 自动装配；本类仅作为 module 自暴露入口（launcher 引入依赖即生效，无需全局扫描）。
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = DbInfraAutoConfiguration.class)
public class DbInfraAutoConfiguration {
}
