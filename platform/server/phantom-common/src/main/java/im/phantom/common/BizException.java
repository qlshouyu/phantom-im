package im.phantom.common;

/**
 * 业务异常，携带协议错误码。处理器可抛出，由网关/适配层转为对应 code 的响应帧。
 */
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
