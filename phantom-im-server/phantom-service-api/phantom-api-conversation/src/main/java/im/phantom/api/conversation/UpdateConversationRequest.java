package im.phantom.api.conversation;

/** 更新会话置顶或免打扰状态；null 表示不修改。 */
public record UpdateConversationRequest(Boolean pinned, Boolean muted) {}
