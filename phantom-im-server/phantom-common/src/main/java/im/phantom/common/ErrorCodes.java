package im.phantom.common;

/** 错误码（与协议设计第 7 节一致，长连接与 HTTP 共用一套）。 */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final int OK = 0;

    // 1xxx 通用
    public static final int INVALID_PARAM = 1001;
    public static final int SERVER_ERROR = 1002;
    public static final int RATE_LIMITED = 1003;

    // 2xxx 鉴权/设备
    public static final int TOKEN_EXPIRED = 2001;
    public static final int TOKEN_REVOKED = 2002;
    public static final int AUTH_FAILED = 2003;
    public static final int USER_DISABLED = 2004;
    public static final int DEVICE_LIMIT_EXCEEDED = 2005;

    // 3xxx 关系/权限
    public static final int USER_NOT_FOUND = 3001;
    public static final int NOT_FRIEND = 3002;
    public static final int IN_MY_BLACKLIST = 3003;
    public static final int REJECTED_BY_PEER = 3004;
    public static final int FRIEND_LIMIT_EXCEEDED = 3005;
    public static final int DUPLICATE_APPLY = 3006;
    public static final int ALREADY_HANDLED = 3007;

    // 4xxx 消息
    public static final int MSG_TOO_LONG = 4001;
    public static final int CONV_INVALID = 4002;
    public static final int RECALL_TIMEOUT = 4003;
    public static final int ALREADY_RECALLED = 4004;
    public static final int CONTENT_BLOCKED = 4005;
    public static final int MEDIA_TOO_LARGE = 4006;
    public static final int BURN_MODE_FORBIDDEN = 4007;

    // 5xxx 群
    public static final int GROUP_FULL = 5001;
    public static final int NO_PERMISSION = 5002;
    public static final int MUTED = 5003;
    public static final int GROUP_DISSOLVED = 5004;
    public static final int MENTION_ALL_RATE_LIMITED = 5005;
    public static final int NOT_IN_GROUP = 5006;

    // 6xxx 风控/合规
    public static final int ACCOUNT_RESTRICTED = 6001;
    public static final int ACCOUNT_BANNED = 6002;
    public static final int MINOR_MODE_FORBIDDEN = 6003;
}
