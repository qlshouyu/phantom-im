package im.phantom.common.http;

import im.phantom.common.ErrorCodes;

/** HTTP 与 SDK HTTP 通道共用的统一响应包。 */
public record ApiResponse<T>(int code, String message, T data, String requestId) {

    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(ErrorCodes.OK, "ok", data, requestId);
    }

    public static <T> ApiResponse<T> error(int code, String message, String requestId) {
        return new ApiResponse<>(code, message, null, requestId);
    }
}
