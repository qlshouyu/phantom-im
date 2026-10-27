package im.phantom.api.message;

/** HTTP 漫游消息投影；content_json 由 protobuf JSON 映射生成并保持类型扩展性。 */
public record MessageViewDto(
        String msgId,
        String clientMsgId,
        String convId,
        String convType,
        String senderId,
        String msgType,
        String contentJson,
        long convSeq,
        long serverTime,
        String status,
        boolean recalled,
        boolean restricted,
        boolean isRequest,
        String replyToMsgId,
        String replySenderId,
        String replySenderName,
        String replyDigest,
        int burnAfterReadTtlSeconds
) {}
