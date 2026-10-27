package im.phantom.api.conversation;

/** 清空草稿；editedAt 用于多端冲突仲裁。 */
public record ClearDraftRequest(Long editedAt) {}
