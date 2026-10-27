package im.phantom.api.config;

/** 应用可用状态查询契约。 */
public interface AppStatusProvider {

    boolean isActive(String appId);
}
