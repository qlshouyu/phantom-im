package im.phantom.api.conversation;

/** 标记已读到指定 conv_seq。 */
public record MarkReadRequest(long readConvSeq) {}
