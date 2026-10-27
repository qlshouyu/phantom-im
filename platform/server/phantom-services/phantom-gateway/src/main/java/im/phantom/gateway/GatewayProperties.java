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
    /** 发送任务工作线程数。 */
    private int sendWorkerThreads = 4;
    /** 进程内发送任务总容量。 */
    private int sendQueueCapacity = 2000;
    /** 单连接等待发送的任务容量。 */
    private int sendPerConnectionCapacity = 8;
    /** 发送任务排队等待执行的最大时间。 */
    private long sendTaskTimeoutMs = 5_000;
    /** 停机时等待发送任务排空的最长时间。 */
    private long sendShutdownTimeoutMs = 10_000;
    /** 单设备 WebSocket 发送时限。 */
    private int wsSendTimeLimitMs = 2_000;
    /** 单设备 WebSocket 发送缓冲上限。 */
    private int wsSendBufferSizeBytes = 512 * 1024;
    /** 单用户 WebSocket 发送限流上限与窗口。 */
    private int sendRateLimit = 2400;
    private int sendRateWindowSeconds = 60;

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
    public int getSendWorkerThreads() { return sendWorkerThreads; }
    public void setSendWorkerThreads(int v) { this.sendWorkerThreads = v; }
    public int getSendQueueCapacity() { return sendQueueCapacity; }
    public void setSendQueueCapacity(int v) { this.sendQueueCapacity = v; }
    public int getSendPerConnectionCapacity() { return sendPerConnectionCapacity; }
    public void setSendPerConnectionCapacity(int v) { this.sendPerConnectionCapacity = v; }
    public long getSendTaskTimeoutMs() { return sendTaskTimeoutMs; }
    public void setSendTaskTimeoutMs(long v) { this.sendTaskTimeoutMs = v; }
    public long getSendShutdownTimeoutMs() { return sendShutdownTimeoutMs; }
    public void setSendShutdownTimeoutMs(long v) { this.sendShutdownTimeoutMs = v; }
    public int getWsSendTimeLimitMs() { return wsSendTimeLimitMs; }
    public void setWsSendTimeLimitMs(int v) { this.wsSendTimeLimitMs = v; }
    public int getWsSendBufferSizeBytes() { return wsSendBufferSizeBytes; }
    public void setWsSendBufferSizeBytes(int v) { this.wsSendBufferSizeBytes = v; }
    public int getSendRateLimit() { return sendRateLimit; }
    public void setSendRateLimit(int v) { this.sendRateLimit = v; }
    public int getSendRateWindowSeconds() { return sendRateWindowSeconds; }
    public void setSendRateWindowSeconds(int v) { this.sendRateWindowSeconds = v; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
}
