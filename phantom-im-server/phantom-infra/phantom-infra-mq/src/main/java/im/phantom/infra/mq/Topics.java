package im.phantom.infra.mq;

/** Topic 常量（MQ 架构 §4）。 */
public final class Topics {
    private Topics() {}

    /** 消息事件：顺序消息，tag 区分 new_msg/recall。 */
    public static final String MSG_EVENT = "TOPIC_MSG_EVENT";

    /** 领域状态事件：关系、群、资料、已读、会话和草稿。 */
    public static final String SYNC_EVENT = "TOPIC_SYNC_EVENT";

    /** 离线推送事件。 */
    public static final String PUSH_EVENT = "TOPIC_PUSH_EVENT";

    /** 搜索索引更新事件。 */
    public static final String SEARCH_INDEX_EVENT = "TOPIC_SEARCH_INDEX";

    /** 风控异步复审与审计事件。 */
    public static final String RISK_AUDIT_EVENT = "TOPIC_RISK_AUDIT";

    /** 媒体清理事件。 */
    public static final String MEDIA_CLEANUP_EVENT = "TOPIC_MEDIA_CLEANUP";

    /** 仅用于普通消息生产者启动预热，无消费者订阅。 */
    public static final String PRODUCER_WARMUP = "TOPIC_PRODUCER_WARMUP";

    public static final String TAG_NEW_MSG = "new_msg";
    public static final String TAG_RECALL = "recall";
    public static final String TAG_STATE_CHANGED = "state_changed";
    public static final String TAG_PUSH = "push";
    public static final String TAG_INDEX = "index";
    public static final String TAG_AUDIT = "audit";
    public static final String TAG_CLEANUP = "cleanup";
    /** 仅供生产者启动预热，消费者不订阅。 */
    public static final String TAG_WARMUP = "warmup";
}
