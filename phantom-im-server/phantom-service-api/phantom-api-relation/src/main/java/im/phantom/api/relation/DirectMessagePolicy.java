package im.phantom.api.relation;

/** 单聊收单的关系策略：正常、消息请求或受限投递。 */
public record DirectMessagePolicy(boolean restricted, boolean messageRequest) {

    public static final DirectMessagePolicy ALLOWED = new DirectMessagePolicy(false, false);
    public static final DirectMessagePolicy MESSAGE_REQUEST = new DirectMessagePolicy(false, true);
    public static final DirectMessagePolicy RESTRICTED = new DirectMessagePolicy(true, false);
}
