package im.phantom.api.message;

import java.util.List;

/** 会话历史分页，nextCursor 为下一批消息之前的最小 conv_seq。 */
public record MessagePageDto(List<MessageViewDto> items, long nextCursor, boolean hasMore) {
    public MessagePageDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
