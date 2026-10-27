package im.phantom.relation.adapter;

import im.phantom.api.relation.RelationService;
import im.phantom.api.relation.UserCardDto;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.HttpAuthAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 按 Phantom ID 查询资料卡；查询范围固定为当前应用。 */
@RestController
@RequestMapping("/users")
public class UserCardController {

    private final RelationService relationService;

    public UserCardController(RelationService relationService) {
        this.relationService = relationService;
    }

    @GetMapping("/me")
    public ApiResponse<UserCardDto> me(
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(relationService.getUserCard(appId, userId, userId), requestId);
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserCardDto> card(
            @PathVariable("userId") String targetId,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String viewerId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(relationService.getUserCard(appId, viewerId, targetId), requestId);
    }
}
