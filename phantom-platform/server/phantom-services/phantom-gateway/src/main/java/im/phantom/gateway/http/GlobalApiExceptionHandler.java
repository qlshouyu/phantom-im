package im.phantom.gateway.http;

import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.http.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一将业务异常映射为 ApiResponse 与合理 HTTP 状态。 */
@RestControllerAdvice
public class GlobalApiExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handle(BizException error) {
        return ResponseEntity.status(statusOf(error.code()))
                .body(ApiResponse.error(error.code(), error.getMessage(), null));
    }

    private HttpStatus statusOf(int code) {
        return switch (code) {
            case ErrorCodes.AUTH_FAILED, ErrorCodes.TOKEN_EXPIRED, ErrorCodes.TOKEN_REVOKED -> HttpStatus.UNAUTHORIZED;
            case ErrorCodes.USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.NO_PERMISSION, ErrorCodes.IN_MY_BLACKLIST, ErrorCodes.REJECTED_BY_PEER,
                    ErrorCodes.ACCOUNT_RESTRICTED, ErrorCodes.ACCOUNT_BANNED,
                    ErrorCodes.MINOR_MODE_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ErrorCodes.DUPLICATE_APPLY, ErrorCodes.ALREADY_HANDLED,
                    ErrorCodes.FRIEND_LIMIT_EXCEEDED, ErrorCodes.GROUP_FULL -> HttpStatus.CONFLICT;
            case ErrorCodes.RATE_LIMITED, ErrorCodes.MENTION_ALL_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
