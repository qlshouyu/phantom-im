package im.phantom.api.config;

/** 应用功能配置读取契约。 */
public interface AppConfigProvider {

    AppFeatureConfigDto getConfig(String appId);
}
