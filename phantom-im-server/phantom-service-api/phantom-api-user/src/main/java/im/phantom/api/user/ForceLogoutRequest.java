package im.phantom.api.user;

/** 强制下线请求；deviceId 为空表示全部设备。 */
public record ForceLogoutRequest(String deviceId) {}
