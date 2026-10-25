package im.phantom.api.relation;

/** 个人二维码名片；token 仅生成或刷新时返回。 */
public record QrCardDto(String cardId, String token, String cardUrl, Long expiresAt) {}
