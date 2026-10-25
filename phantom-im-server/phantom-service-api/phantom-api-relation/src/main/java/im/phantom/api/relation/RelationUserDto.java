package im.phantom.api.relation;

/** 关系链中可安全展示的用户摘要。 */
public record RelationUserDto(String userId, String nickname, String avatarUrl) {}
