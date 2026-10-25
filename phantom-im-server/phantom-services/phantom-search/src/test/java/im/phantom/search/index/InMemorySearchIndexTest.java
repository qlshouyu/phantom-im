package im.phantom.search.index;

import im.phantom.search.spi.SearchDocument;
import im.phantom.search.spi.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySearchIndexTest {

    @Test
    void filtersByOwnerTypeAndKeyword() {
        InMemorySearchIndex index = new InMemorySearchIndex();
        index.upsert(new SearchDocument("d1", "a1", "CONTACT", "u1", null, null,
                "张三", "备注财务", Map.of()));
        index.upsert(new SearchDocument("d2", "a1", "MESSAGE", "u2", "c1", "m1",
                "项目群", "张三说今天开会", Map.of()));

        assertThat(index.search(new SearchQuery("a1", "u1", "CONTACT", null, "财务", 20)))
                .extracting(SearchDocument::docId).containsExactly("d1");
        assertThat(index.search(new SearchQuery("a1", "u1", null, "c1", "开会", 20))).isEmpty();
    }

    @Test
    void deleteRemovesDocument() {
        InMemorySearchIndex index = new InMemorySearchIndex();
        index.upsert(new SearchDocument("d1", "a1", "GROUP", "u1", null, null,
                "项目群", "", Map.of()));

        index.delete("d1");

        assertThat(index.search(new SearchQuery("a1", "u1", "GROUP", null, "项目", 20))).isEmpty();
    }
}
