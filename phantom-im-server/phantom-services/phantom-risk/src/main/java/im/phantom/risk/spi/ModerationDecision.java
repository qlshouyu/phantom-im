package im.phantom.risk.spi;

/** 内容审核结果。 */
public record ModerationDecision(boolean allowed, boolean reviewRequired, String reasonCode) {

    public static ModerationDecision allow() {
        return new ModerationDecision(true, false, null);
    }

    public static ModerationDecision block(String reasonCode) {
        return new ModerationDecision(false, false, reasonCode);
    }

    public static ModerationDecision review(String reasonCode) {
        return new ModerationDecision(true, true, reasonCode);
    }
}
