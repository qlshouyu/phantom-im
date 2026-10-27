package im.phantom.message.adapter;

import im.phantom.api.message.ForwardMessageRequest;
import im.phantom.api.message.MessageActionDto;
import im.phantom.api.message.MessagePageDto;
import im.phantom.api.message.MessageService;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 历史漫游、转发和撤回 HTTP API。 */
@RestController
public class MessageController {

    private final MessageService messages;

    public MessageController(MessageService messages) {
        this.messages = messages;
    }

    @GetMapping(ApiRoutes.CONVERSATION_MESSAGES)
    public ApiResponse<MessagePageDto> history(
            @PathVariable("convId") String convId,
            @RequestParam(value = "before_conv_seq", defaultValue = "0") long beforeConvSeq,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(messages.history(userId, convId, beforeConvSeq, limit), requestId);
    }

    @PostMapping(ApiRoutes.MESSAGE_FORWARD)
    public ApiResponse<MessageActionDto> forward(
            @RequestBody ForwardMessageRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(messages.forward(appId, userId, request), requestId);
    }

    @PostMapping(ApiRoutes.MESSAGE_RECALL)
    public ApiResponse<MessageActionDto> recall(
            @PathVariable("msgId") String msgId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(messages.recall(userId, msgId), requestId);
    }

    @DeleteMapping(ApiRoutes.MESSAGE_LOCAL_DELETE)
    public ApiResponse<Void> deleteLocal(
            @PathVariable("msgId") String msgId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        messages.deleteLocal(userId, msgId);
        return ApiResponse.ok(null, requestId);
    }

    @DeleteMapping(ApiRoutes.CONVERSATION_MESSAGES_CLEAR)
    public ApiResponse<Void> clearHistory(
            @PathVariable("convId") String convId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        messages.clearHistory(userId, convId);
        return ApiResponse.ok(null, requestId);
    }
}
