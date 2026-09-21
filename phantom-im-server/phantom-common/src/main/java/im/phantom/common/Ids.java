package im.phantom.common;

import java.util.UUID;

/** 服务端标识生成。 */
public final class Ids {
    private Ids() {}

    /** 服务端全局唯一 msg_id。M0 用 UUID（无连字符）即可满足唯一与幂等目标标识。 */
    public static String newMsgId() {
        return "m_" + UUID.randomUUID().toString().replace("-", "");
    }
}
