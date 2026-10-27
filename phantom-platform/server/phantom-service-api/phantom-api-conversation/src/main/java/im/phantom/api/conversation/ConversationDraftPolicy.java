package im.phantom.api.conversation;

/** SDK 与服务端共用的草稿同步参数。 */
public final class ConversationDraftPolicy {

    /** 客户端停止输入后 2 秒再同步草稿，避免逐字符请求。 */
    public static final long SYNC_DEBOUNCE_MILLIS = 2_000L;

    private ConversationDraftPolicy() {}
}
