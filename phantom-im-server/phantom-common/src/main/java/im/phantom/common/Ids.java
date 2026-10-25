package im.phantom.common;

import java.util.Objects;
import java.util.UUID;

/** 服务端标识生成。 */
public final class Ids {
    private Ids() {}

    /** 生成带领域前缀的全局唯一 ID。 */
    public static String newId(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("ID prefix 不能为空");
        }
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 服务端全局唯一 msg_id。 */
    public static String newMsgId() {
        return newId("m");
    }

    public static String newAppId() {
        return newId("app");
    }

    public static String newUserId() {
        return newId("u");
    }

    public static String newGroupId() {
        return newId("g");
    }

    public static String newMediaId() {
        return newId("media");
    }

    public static String newUploadId() {
        return newId("upload");
    }

    public static String newRequestId() {
        return newId("req");
    }

    public static String newFriendRequestId() {
        return newId("fr");
    }

    public static String newFriendGroupId() {
        return newId("fg");
    }

    public static String newQrCardId() {
        return newId("qr");
    }

    public static String newEventId() {
        return newId("evt");
    }
}
