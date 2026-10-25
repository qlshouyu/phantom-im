package im.phantom.api.conversation;

/** 保存草稿；editedAt 为客户端最后编辑时间。 */
public record SaveDraftRequest(String draft, Long editedAt) {}
