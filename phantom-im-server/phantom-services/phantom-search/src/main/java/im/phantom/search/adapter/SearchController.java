package im.phantom.search.adapter;

import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import im.phantom.search.app.SearchAppService;
import im.phantom.search.app.SearchDtos.ConversationSearch;
import im.phantom.search.app.SearchDtos.SearchResult;
import im.phantom.search.app.SearchDtos.SearchSuggestions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {
    private final SearchAppService search;
    public SearchController(SearchAppService search) { this.search = search; }

    @GetMapping(ApiRoutes.SEARCH)
    public ApiResponse<SearchResult> search(@RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(search.search(userId, query, limit), requestId);
    }

    @GetMapping(ApiRoutes.CONVERSATION_SEARCH)
    public ApiResponse<ConversationSearch> conversation(@PathVariable("convId") String convId,
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(search.searchConversation(userId, convId, query, limit), requestId);
    }

    @GetMapping(ApiRoutes.SEARCH_SUGGEST)
    public ApiResponse<SearchSuggestions> suggest(@RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(search.suggest(userId, query, limit), requestId);
    }
}
