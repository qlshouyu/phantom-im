package im.phantom.web.auth;

public record PhantomTokenResponse(String userToken, String phantomUserId, String appId,
                                   long expiresAt, long expiresInSeconds) {
}
