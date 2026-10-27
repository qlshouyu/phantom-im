package im.phantom.api.message;

import java.util.List;

/** 消息操作结果。 */
public record MessageActionDto(String msgId, List<String> targetMsgIds) {
    public MessageActionDto {
        targetMsgIds = targetMsgIds == null ? List.of() : List.copyOf(targetMsgIds);
    }
}
