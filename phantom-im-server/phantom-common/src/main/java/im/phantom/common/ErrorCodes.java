package im.phantom.common;

/**
 * 错误码（与协议设计第 7 节一致，长连接与 HTTP 共用一套）。M0 只用到其中子集。
 */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final int OK = 0;

    // 1xxx 通用
    public static final int INVALID_PARAM = 1001;
    public static final int SERVER_ERROR = 1002;

    // 2xxx 鉴权/设备
    public static final int TOKEN_EXPIRED = 2001;
    public static final int TOKEN_REVOKED = 2002;
    public static final int AUTH_FAILED = 2003;
    public static final int USER_DISABLED = 2004;
    public static final int DEVICE_LIMIT_EXCEEDED = 2005;

    // 4xxx 消息
    public static final int MSG_TOO_LONG = 4001;
    public static final int CONV_INVALID = 4002;
}
