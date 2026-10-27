# Easy IM BFF

Easy IM 消费方的账号服务与 HTTP BFF。独立 Spring Boot 4 / JDK 21 Maven 工程，不加入 `platform/server` 聚合工程。

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
- 默认 UI 构建目录：`../clients/web/dist/`

Maven `groupId`、`artifactId` 和可执行 Jar 名称暂未更名，仍是 `phantom-im-web-bff`。完整说明见 [`../README.md`](../README.md)。
