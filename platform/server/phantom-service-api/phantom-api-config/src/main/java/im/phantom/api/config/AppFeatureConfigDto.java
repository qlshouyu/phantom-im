package im.phantom.api.config;

/** 应用级功能与设备策略。 */
public record AppFeatureConfigDto(
        boolean relationHosted,
        boolean readReceiptEnabled,
        boolean burnAfterReadEnabled,
        int burnAfterReadTtlSeconds,
        boolean sdkProfileWriteEnabled,
        int maxDevices,
        boolean riskCheckEnabled
) {
    /** 兼容旧调用方；缺省风险检查关闭。 */
    public AppFeatureConfigDto(boolean relationHosted, boolean readReceiptEnabled,
                               boolean burnAfterReadEnabled, int burnAfterReadTtlSeconds,
                               boolean sdkProfileWriteEnabled, int maxDevices) {
        this(relationHosted, readReceiptEnabled, burnAfterReadEnabled, burnAfterReadTtlSeconds,
                sdkProfileWriteEnabled, maxDevices, false);
    }

    public static AppFeatureConfigDto defaults() {
        return new AppFeatureConfigDto(true, true, false, 0, true, 3, false);
    }
}
