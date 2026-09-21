package im.phantom.common;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;

/** Envelope 组帧辅助。 */
public final class Envelopes {
    private Envelopes() {}

    public static final int VERSION = 1;

    /** 响应帧：回带请求 cid。 */
    public static Envelope resp(Command cmd, long cid, int code, MessageLite payload) {
        Envelope.Builder b = Envelope.newBuilder()
                .setVersion(VERSION).setCmd(cmd).setCid(cid).setCode(code);
        if (payload != null) {
            b.setPayload(payload.toByteString());
        }
        return b.build();
    }

    /** 服务端主动推送帧：cid=0。 */
    public static Envelope push(Command cmd, MessageLite payload) {
        Envelope.Builder b = Envelope.newBuilder()
                .setVersion(VERSION).setCmd(cmd).setCid(0).setCode(0);
        if (payload != null) {
            b.setPayload(payload.toByteString());
        }
        return b.build();
    }

    public static ByteString payload(MessageLite m) {
        return m.toByteString();
    }
}
