# phantom-im-web-bff

Web IM 接入方账号服务与 HTTP BFF。独立 Spring Boot 4 / JDK 21 Maven 工程，不加入 `phantom-im-server` 聚合工程。

```bash
cp .env.example .env
set -a; source .env; set +a
mvn -s .mvn/settings.xml test
mvn -s .mvn/settings.xml spring-boot:run
```

- 健康检查：`GET http://127.0.0.1:28090/actuator/health`
- 运行配置：`GET http://127.0.0.1:28090/api/runtime-config`
- Phantom 代理：`/api/phantom/**`
- 数据源：仅 `phantom_web_im`
- Spring Session：Redis DB 1，前缀 `phantom:web-im:session:`

完整说明见仓库根目录 `docs/web-im-consumer.md`。
