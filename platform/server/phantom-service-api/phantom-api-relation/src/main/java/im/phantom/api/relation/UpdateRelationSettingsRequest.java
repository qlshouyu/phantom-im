package im.phantom.api.relation;

/** 更新关系设置；当前仅开放自动通过开关。 */
public record UpdateRelationSettingsRequest(Boolean autoAcceptRequests) {}
