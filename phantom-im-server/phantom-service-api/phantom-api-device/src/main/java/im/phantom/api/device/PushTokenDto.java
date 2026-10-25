package im.phantom.api.device;

/** 设备推送 Token 注册项。 */
public record PushTokenDto(String token, int deviceType, String provider) {}
