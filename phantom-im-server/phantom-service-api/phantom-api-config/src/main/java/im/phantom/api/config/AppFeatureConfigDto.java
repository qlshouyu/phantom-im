package im.phantom.api.config;

/** 应用级功能与设备策略。 */
public record AppFeatureConfigDto(
        boolean relationHosted,
        boolean readReceiptEnabled,
        boolean burnAfterReadEnabled,
        int burnAfterReadTtlSeconds,
        boolean sdkProfileWriteEnabled,
        int maxDevices
) {
    public static AppFeatureConfigDto defaults() {
        return new AppFeatureConfigDto(true, true, false, 0, true, 3);
    }
}
