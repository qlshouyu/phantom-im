package im.phantom.common;

/**
 * 统一分页请求。游标为不透明字符串；空游标表示第一页。
 */
public record PageRequest(String cursor, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public PageRequest {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    public static PageRequest firstPage() {
        return new PageRequest(null, DEFAULT_LIMIT);
    }

    public boolean first() {
        return cursor == null || cursor.isBlank();
    }
}
