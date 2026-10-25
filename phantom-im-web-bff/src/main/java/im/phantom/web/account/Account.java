package im.phantom.web.account;

public record Account(
        String accountId,
        String appId,
        String loginName,
        String nickname,
        String passwordHash,
        String phantomUserId,
        AccountStatus status,
        long createdAt,
        long updatedAt) {
}
