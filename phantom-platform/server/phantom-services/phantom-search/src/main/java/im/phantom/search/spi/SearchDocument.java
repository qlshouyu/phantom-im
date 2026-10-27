package im.phantom.search.spi;

import java.util.Map;

/** 搜索索引文档；owner 维度用于权限过滤。 */
public record SearchDocument(
        String docId,
        String appId,
        String docType,
        String ownerId,
        String convId,
        String msgId,
        String title,
        String content,
        Map<String, String> metadata
) {
    public SearchDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
