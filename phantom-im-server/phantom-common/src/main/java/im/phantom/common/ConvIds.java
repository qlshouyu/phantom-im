package im.phantom.common;

/**
 * 会话 ID 规则。
 *
 * <p>M0 简化：单聊 conv_id 采用可逆的排序拼接 {@code si_{uidMin}__{uidMax}}，
 * 使扇出服务无需额外的会话成员表即可从 conv_id 推导双方成员（walking skeleton）。
 * 协议设计中的 {@code si_ + hash(sorted uid)} 需要会话/成员服务支撑，留待后续里程碑；
 * 二者都满足「从任一入口进入同一好友会话必得同一 conv_id」（DC-01/02/03）。
 */
public final class ConvIds {
    private ConvIds() {}

    public static final String SINGLE_PREFIX = "si_";
    private static final String SEP = "__";

    /** 由双方 userId 生成单聊 conv_id（顺序无关）。 */
    public static String single(String a, String b) {
        String lo = a.compareTo(b) <= 0 ? a : b;
        String hi = a.compareTo(b) <= 0 ? b : a;
        return SINGLE_PREFIX + lo + SEP + hi;
    }

    public static boolean isSingle(String convId) {
        return convId != null && convId.startsWith(SINGLE_PREFIX) && convId.contains(SEP);
    }

    /** 从单聊 conv_id 解析双方成员 [uidMin, uidMax]。 */
    public static String[] participants(String convId) {
        if (!isSingle(convId)) {
            throw new IllegalArgumentException("非法单聊 conv_id: " + convId);
        }
        return convId.substring(SINGLE_PREFIX.length()).split(SEP, 2);
    }
}
