package im.phantom.api.relation;

/** 当前用户的关系设置。 */
public record RelationSettingsDto(boolean autoAcceptRequests, int friendLimit) {}
