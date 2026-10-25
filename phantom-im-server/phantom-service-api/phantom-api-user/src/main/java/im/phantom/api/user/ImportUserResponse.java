package im.phantom.api.user;

/** 用户导入幂等响应。 */
public record ImportUserResponse(String userId, boolean created) {}
