package im.phantom.fanout.app;

import im.phantom.api.route.OnlineDeliveryService;
import im.phantom.api.sync.UserSyncEventWriter;
import im.phantom.api.route.OfflinePushNotifier;
import im.phantom.common.ConvIds;
import im.phantom.common.Ids;
import im.phantom.fanout.infra.UserTimelineWriteDao;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgPush;
import im.phantom.proto.v1.NewMessageEvent;
import im.phantom.proto.v1.RestrictedDeliveryEvent;
import im.phantom.proto.v1.SyncEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 扇出用例（message-fanout-delivery spec）：为会话内每个接收者分配 user_seq、
 * 写用户同步 Timeline（内联 NewMessageEvent），并触发在线投递。
 * 幂等键 (user_id, msg_id)，重复消费不产生重复事件。
 */
@Service
public class FanoutService {

    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);
    /** SyncEvent.new_message 字段号（event_type 判别）。 */
    private static final int EVENT_TYPE_NEW_MESSAGE = 10;

    private final UserTimelineWriteDao dao;
    private final SeqAllocator seqAllocator;
    private final OnlineDeliveryService delivery;
    private final UserSyncEventWriter syncEventWriter;
    private final ObjectProvider<OfflinePushNotifier> offlinePush;

    public FanoutService(UserTimelineWriteDao dao, SeqAllocator seqAllocator, OnlineDeliveryService delivery) {
        this(dao, seqAllocator, delivery, null, null);
    }

    @Autowired
    public FanoutService(UserTimelineWriteDao dao, SeqAllocator seqAllocator, OnlineDeliveryService delivery,
                         UserSyncEventWriter syncEventWriter, ObjectProvider<OfflinePushNotifier> offlinePush) {
        this.dao = dao;
        this.seqAllocator = seqAllocator;
        this.delivery = delivery;
        this.syncEventWriter = syncEventWriter;
        this.offlinePush = offlinePush;
    }

    public void onNewMessage(Msg msg) {
        // 单聊双向：收发双方都写扩散（本人他端一致 MD-12）
        String[] receivers;
        if (msg.getRestricted()) {
            receivers = new String[]{msg.getSenderId()};
        } else if (ConvIds.isSingle(msg.getConvId())) {
            receivers = ConvIds.participants(msg.getConvId());
        } else {
            receivers = dao.listParticipants(msg.getConvId()).toArray(String[]::new);
        }
        for (String receiver : receivers) {
            fanoutTo(receiver, msg);
        }
        if (msg.getRestricted() && syncEventWriter != null) {
            syncEventWriter.append(msg.getSenderId(), Ids.newEventId(), SyncEvent.newBuilder()
                    .setEventTime(msg.getServerTime())
                    .setRestrictedDelivery(RestrictedDeliveryEvent.newBuilder()
                            .setConvId(msg.getConvId())
                            .setMsgId(msg.getMsgId())
                            .setTemplateId("delivery.maybe_blocked"))
                    .build());
        }
    }

    private void fanoutTo(String receiver, Msg msg) {
        if (dao.exists(receiver, msg.getMsgId())) {
            return; // 幂等：已扇出
        }
        long userSeq = seqAllocator.nextUserSeq(receiver);
        SyncEvent event = SyncEvent.newBuilder()
                .setUserSeq(userSeq)
                .setEventTime(msg.getServerTime())
                .setNewMessage(NewMessageEvent.newBuilder()
                        .setMsg(msg)
                        .setConvId(msg.getConvId())
                        .setConvSeq(msg.getConvSeq())
                        .setMsgId(msg.getMsgId())
                        .setIsRequest(msg.getIsRequest())
                        .build())
                .build();
        try {
            dao.insert(receiver, userSeq, EVENT_TYPE_NEW_MESSAGE, msg.getMsgId(),
                    event.toByteArray(), msg.getServerTime());
        } catch (DuplicateKeyException dup) {
            return; // 并发重复消费，幂等丢弃
        }
        // 触发在线投递（无在线连接则留待 SyncReq 补齐）
        MsgPush push = MsgPush.newBuilder().setMsg(msg).setUserSeq(userSeq).build();
        int n = delivery.deliver(receiver, push);
        if (n == 0 && offlinePush != null) {
            OfflinePushNotifier notifier = offlinePush.getIfAvailable();
            if (notifier != null) {
                notifier.notifyOffline(receiver, msg);
            }
        }
        log.info("扇出 receiver={} userSeq={} msgId={} 在线下发={}", receiver, userSeq, msg.getMsgId(), n);
    }
}
