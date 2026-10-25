package im.phantom.api.route;

import im.phantom.proto.v1.MsgPush;
import im.phantom.proto.v1.Envelope;

/**
 * 在线投递契约：为某接收者查路由并下发 MsgPush。
 * 由 push module 本地实现；fanout module 写完接收者同步 Timeline 后调用它。
 */
public interface OnlineDeliveryService {

    /**
     * 投递一条消息给某接收者的全部在线端。无在线连接则不投递（留待 SyncReq 补齐）。
     * @return 实际下发的在线连接数（0 表示接收者当前离线）
     */
    int deliver(String receiverId, MsgPush push);

    /** 向某接收者全部在线端下发任意服务端 Push 帧。 */
    int deliverEnvelope(String receiverId, Envelope envelope);
}
