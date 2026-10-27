package im.phantom.search.index;

import im.phantom.search.spi.SearchDocument;
import im.phantom.search.spi.SearchIndex;
import im.phantom.search.spi.SearchQuery;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** 内存搜索实现，仅用于开发和测试；重启后由领域事件重建。 */
public class InMemorySearchIndex implements SearchIndex {

    private final ConcurrentHashMap<String, SearchDocument> documents = new ConcurrentHashMap<>();

    @Override
    public void upsert(SearchDocument document) {
        documents.put(document.docId(), document);
    }

    @Override
    public void delete(String docId) {
        documents.remove(docId);
    }

    @Override
    public List<SearchDocument> search(SearchQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().toLowerCase(Locale.ROOT);
        return documents.values().stream()
                .filter(doc -> doc.appId().equals(query.appId()))
                .filter(doc -> doc.ownerId().equals(query.ownerId()))
                .filter(doc -> query.docType() == null || query.docType().isBlank()
                        || doc.docType().equals(query.docType()))
                .filter(doc -> query.convId() == null || query.convId().isBlank()
                        || query.convId().equals(doc.convId()))
                .filter(doc -> keyword.isBlank()
                        || contains(doc.title(), keyword) || contains(doc.content(), keyword))
                .sorted(Comparator.comparing(SearchDocument::docId))
                .limit(query.limit())
                .toList();
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }
}
