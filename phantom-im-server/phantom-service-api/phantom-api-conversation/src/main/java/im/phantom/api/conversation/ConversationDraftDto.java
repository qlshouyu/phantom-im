package im.phantom.api.conversation;

/** 服务端最终生效的草稿版本。 */
public record ConversationDraftDto(String convId, String draft, long editedAt) {}
