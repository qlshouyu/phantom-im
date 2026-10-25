package im.phantom.common;

import java.util.List;

/** 统一分页结果。 */
public record PageResult<T>(List<T> items, String nextCursor, boolean hasMore) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), null, false);
    }
}
