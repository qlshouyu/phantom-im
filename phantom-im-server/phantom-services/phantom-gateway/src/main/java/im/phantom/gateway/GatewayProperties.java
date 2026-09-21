package im.phantom.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关配置。M0 单应用：app-id + app-secret 本地注入用于 UserToken 验签。 */
@ConfigurationProperties(prefix = "phantom.gateway")
public class GatewayProperties {
    /** 本网关实例 id（路由表登记用；M0 单实例）。 */
    private String id = "gw-1";
    /** 建议心跳间隔（秒）。 */
    private int heartbeatIntervalS = 270;
    /** 认证超时（秒）：连接建立后未认证则断开。 */
    private int authTimeoutS = 10;

    /** M0 单应用：应用标识。 */
    private String appId = "app_demo";
    /** M0 单应用：AppSecret（HS256 验签密钥）。生产由配置中心/多租户路由表下发。 */
    private String appSecret = "phantom_demo_secret_please_change";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getHeartbeatIntervalS() { return heartbeatIntervalS; }
    public void setHeartbeatIntervalS(int v) { this.heartbeatIntervalS = v; }
    public int getAuthTimeoutS() { return authTimeoutS; }
    public void setAuthTimeoutS(int v) { this.authTimeoutS = v; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
}
