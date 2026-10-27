package im.phantom.search.spi;

/** 搜索查询；docType 为空表示全部类型。 */
public record SearchQuery(
        String appId,
        String ownerId,
        String docType,
        String convId,
        String keyword,
        int limit
) {
    public SearchQuery {
        limit = limit <= 0 ? 20 : Math.min(limit, 100);
    }
}
