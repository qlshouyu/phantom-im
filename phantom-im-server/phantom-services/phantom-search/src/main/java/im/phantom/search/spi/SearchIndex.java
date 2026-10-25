package im.phantom.search.spi;

import java.util.List;

/** 搜索索引 SPI；MVP 使用本地实现，后续可替换 OpenSearch。 */
public interface SearchIndex {

    void upsert(SearchDocument document);

    void delete(String docId);

    List<SearchDocument> search(SearchQuery query);
}
