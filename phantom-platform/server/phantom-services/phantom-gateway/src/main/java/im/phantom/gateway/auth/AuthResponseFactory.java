package im.phantom.gateway.auth;

import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.common.BizException;
import im.phantom.proto.v1.AppFeatureConfig;
import im.phantom.proto.v1.AuthResp;
import im.phantom.proto.v1.BurnAfterReadConfig;
import im.phantom.proto.v1.ReadReceiptConfig;
import org.springframework.beans.factory.ObjectProvider;

/** 将应用配置映射为认证响应，缺失配置时使用 M0 默认值。 */
public class AuthResponseFactory {

    private final ObjectProvider<AppConfigProvider> configProvider;

    public AuthResponseFactory(ObjectProvider<AppConfigProvider> configProvider) {
        this.configProvider = configProvider;
    }

    public AuthResp build(String appId, long serverTime, long maxUserSeq, int heartbeatIntervalS) {
        AppFeatureConfigDto config = loadConfig(appId);
        return AuthResp.newBuilder()
                .setServerTime(serverTime)
                .setMaxUserSeq(maxUserSeq)
                .setHeartbeatIntervalS(heartbeatIntervalS)
                .setBurnAfterRead(BurnAfterReadConfig.newBuilder()
                        .setEnabled(config.burnAfterReadEnabled())
                        .setTtlSeconds(config.burnAfterReadTtlSeconds()))
                .setReadReceipt(ReadReceiptConfig.newBuilder().setEnabled(config.readReceiptEnabled()))
                .setAppFeatures(AppFeatureConfig.newBuilder()
                        .setRelationHosted(config.relationHosted())
                        .setSdkProfileWriteEnabled(config.sdkProfileWriteEnabled())
                        .setMaxDevices(config.maxDevices()))
                .build();
    }

    private AppFeatureConfigDto loadConfig(String appId) {
        AppConfigProvider provider = configProvider.getIfAvailable();
        if (provider == null) {
            return AppFeatureConfigDto.defaults();
        }
        try {
            return provider.getConfig(appId);
        } catch (BizException missingApp) {
            return AppFeatureConfigDto.defaults();
        }
    }
}
