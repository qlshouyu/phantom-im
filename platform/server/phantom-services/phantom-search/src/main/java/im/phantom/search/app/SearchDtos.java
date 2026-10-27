package im.phantom.search.app;

import java.util.List;

public final class SearchDtos {
    private SearchDtos() {}
    public record ContactHit(String userId, String nickname, String remark, String avatarUrl) {}
    public record GroupHit(String groupId, String convId, String name, String avatarUrl) {}
    public record MessageHit(String msgId, String convId, String convTitle, String senderId,
                             String digest, long convSeq, long serverTime, boolean recalled,
                             boolean burned) {}
    public record SearchResult(List<ContactHit> contacts, List<GroupHit> groups,
                               List<MessageHit> messages, int contactTotal, int groupTotal,
                               int messageTotal) {
        public SearchResult {
            contacts = contacts == null ? List.of() : List.copyOf(contacts);
            groups = groups == null ? List.of() : List.copyOf(groups);
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
    public record ConversationSearch(String convId, List<MessageHit> messages, int total) {
        public ConversationSearch {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
    public record SearchSuggestions(List<ContactHit> contacts, List<GroupHit> groups, int total) {
        public SearchSuggestions {
            contacts = contacts == null ? List.of() : List.copyOf(contacts);
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }
}
