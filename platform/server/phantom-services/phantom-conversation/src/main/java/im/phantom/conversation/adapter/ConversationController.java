package im.phantom.conversation.adapter;

import im.phantom.api.conversation.ClearDraftRequest;
import im.phantom.api.conversation.ConversationDraftDto;
import im.phantom.api.conversation.ConversationPageDto;
import im.phantom.api.conversation.ConversationService;
import im.phantom.api.conversation.ConversationSnapshotDto;
import im.phantom.api.conversation.MarkReadRequest;
import im.phantom.api.conversation.ReadPositionDto;
import im.phantom.api.conversation.SaveDraftRequest;
import im.phantom.api.conversation.UpdateConversationRequest;
import im.phantom.api.conversation.MessageRequestActionRequest;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 会话快照、状态、已读和草稿 HTTP 接口。 */
@RestController
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping(ApiRoutes.CONVERSATIONS)
    public ApiResponse<ConversationPageDto> conversations(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.listConversations(userId, cursor, limit), requestId);
    }

    @GetMapping(ApiRoutes.CONVERSATION)
    public ApiResponse<ConversationSnapshotDto> conversation(
            @PathVariable("convId") String convId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.getConversation(userId, convId), requestId);
    }

    @PostMapping(ApiRoutes.CONVERSATION)
    public ApiResponse<ConversationSnapshotDto> updateConversation(
            @PathVariable("convId") String convId,
            @RequestBody UpdateConversationRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.updateConversation(userId, convId, request), requestId);
    }

    @DeleteMapping(ApiRoutes.CONVERSATION)
    public ApiResponse<Void> deleteConversation(
            @PathVariable("convId") String convId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        service.deleteConversation(userId, convId);
        return ApiResponse.ok(null, requestId);
    }

    @GetMapping(ApiRoutes.CONVERSATION_READ_POSITIONS)
    public ApiResponse<ReadPositionDto> readPosition(
            @PathVariable("convId") String convId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.getReadPosition(userId, convId), requestId);
    }

    @PutMapping(ApiRoutes.CONVERSATION_READ_POSITIONS)
    public ApiResponse<ReadPositionDto> markRead(
            @PathVariable("convId") String convId,
            @RequestBody MarkReadRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.markRead(appId, userId, convId, request.readConvSeq()), requestId);
    }

    @PutMapping(ApiRoutes.CONVERSATION_DRAFT)
    public ApiResponse<ConversationDraftDto> saveDraft(
            @PathVariable("convId") String convId,
            @RequestBody SaveDraftRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.saveDraft(userId, convId, request.draft(), request.editedAt()), requestId);
    }

    @DeleteMapping(ApiRoutes.CONVERSATION_DRAFT)
    public ApiResponse<ConversationDraftDto> clearDraft(
            @PathVariable("convId") String convId,
            @RequestBody(required = false) ClearDraftRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.clearDraft(userId, convId,
                request == null ? null : request.editedAt()), requestId);
    }

    @PostMapping(ApiRoutes.CONVERSATION_REQUEST)
    public ApiResponse<ConversationSnapshotDto> handleRequest(
            @PathVariable("convId") String convId,
            @RequestBody MessageRequestActionRequest request,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(service.handleMessageRequest(userId, convId, request.accept()), requestId);
    }
}
