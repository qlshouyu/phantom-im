package im.phantom.api.conversation;

/** 用户在一个会话中的服务端已读位置。 */
public record ReadPositionDto(String convId, String userId, long readConvSeq) {}
