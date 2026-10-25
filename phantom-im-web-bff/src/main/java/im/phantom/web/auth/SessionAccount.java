package im.phantom.web.auth;

public record SessionAccount(String accountId, String phantomUserId, String loginName,
                             String nickname, long authenticatedAt) {
}
