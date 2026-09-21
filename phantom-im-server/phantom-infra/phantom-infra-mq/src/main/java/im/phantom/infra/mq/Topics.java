package im.phantom.infra.mq;

/** Topic 常量（MQ 架构 §4）。M0 仅用 TOPIC_MSG_EVENT。 */
public final class Topics {
    private Topics() {}

    /** 消息事件：顺序消息（M0 简化为普通消息，见 design D4），tag 区分 new_msg/recall。 */
    public static final String MSG_EVENT = "TOPIC_MSG_EVENT";

    public static final String TAG_NEW_MSG = "new_msg";
}
