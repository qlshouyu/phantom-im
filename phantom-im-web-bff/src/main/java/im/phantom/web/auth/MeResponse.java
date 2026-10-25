package im.phantom.web.auth;

public record MeResponse(String accountId, String phantomUserId, String loginName, String nickname) {
}
