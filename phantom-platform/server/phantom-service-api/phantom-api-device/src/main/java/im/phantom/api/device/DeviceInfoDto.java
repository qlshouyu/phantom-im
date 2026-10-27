package im.phantom.api.device;

/** 用户在线设备信息。 */
public record DeviceInfoDto(
        String deviceId,
        int deviceType,
        String platform,
        String deviceName,
        long loginAt,
        long lastActiveAt,
        String location,
        boolean foreground
) {}
