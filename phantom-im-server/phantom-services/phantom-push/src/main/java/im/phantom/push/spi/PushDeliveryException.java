package im.phantom.push.spi;

/** 推送通道返回的可分类失败，驱动重试或 Token 清理。 */
public class PushDeliveryException extends RuntimeException {

    private final boolean retryable;
    private final boolean invalidToken;

    public PushDeliveryException(String message, boolean retryable, boolean invalidToken) {
        super(message);
        this.retryable = retryable;
        this.invalidToken = invalidToken;
    }

    public static PushDeliveryException retryable(String message) {
        return new PushDeliveryException(message, true, false);
    }

    public static PushDeliveryException invalidToken(String message) {
        return new PushDeliveryException(message, false, true);
    }

    public boolean retryable() { return retryable; }
    public boolean invalidToken() { return invalidToken; }
}
