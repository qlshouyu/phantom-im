package im.phantom.api.relation;

/** 黑名单列表项。 */
public record BlacklistEntryDto(String userId, String nickname, String avatarUrl, long createdAt) {}
