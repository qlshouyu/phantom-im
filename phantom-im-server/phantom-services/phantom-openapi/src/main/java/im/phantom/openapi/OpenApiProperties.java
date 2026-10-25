package im.phantom.openapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenAPI 管理面配置。secretKey 必须是 Base64 编码的 32 字节密钥。 */
@ConfigurationProperties(prefix = "phantom.openapi")
public class OpenApiProperties {

    private String secretKey = "cGhhbnRvbS1kZW1vLW9ubHktMzJieXRlLWtleSEhISE=";

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}
