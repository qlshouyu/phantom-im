package im.phantom.message.app;

import im.phantom.common.BizException;
import im.phantom.common.ConvIds;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.infra.mq.Topics;
import im.phantom.infra.mq.TxMqProducer;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.message.infra.ConvTimelineDao;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.SendMsgReq;
import im.phantom.proto.v1.SendMsgResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 收单用例（single-chat-messaging spec）：幂等 → 取号 → 落会话 Timeline → 事务消息。
 */
@Service
public class MessageAppService {

    private static final Logger log = LoggerFactory.getLogger(MessageAppService.class);
    private static final int TEXT_MAX = 5000;

    private final ConvTimelineDao dao;
    private final SeqAllocator seqAllocator;
    private final TxMqProducer producer;

    public MessageAppService(ConvTimelineDao dao, SeqAllocator seqAllocator, TxMqProducer producer) {
        this.dao = dao;
        this.seqAllocator = seqAllocator;
        this.producer = producer;
    }

    public SendMsgResp handleSend(String senderId, SendMsgReq req) throws Exception {
        String convId = req.getConvId();
        if (!ConvIds.isSingle(convId)) {
            throw new BizException(ErrorCodes.CONV_INVALID, "M0 仅支持单聊 conv_id: " + convId);
        }
        // 内容校验（TEXT ≤5000）
        if (req.getMsgType() == MsgType.MSG_TYPE_TEXT) {
            String text = req.getContent().getText().getText();
            if (text.length() > TEXT_MAX) {
                throw new BizException(ErrorCodes.MSG_TOO_LONG, "文本超长");
            }
        }

        // 1) 发送幂等：命中即返回首次结果（覆盖超时重发的常见顺序场景）
        ConvTimelineDao.Existing existing = dao.findByClientMsgId(senderId, req.getClientMsgId());
        if (existing != null) {
            return respOf(existing);
        }

        // 2) 取号（会话粒度严格递增）
        long convSeq = seqAllocator.nextConvSeq(convId);
        String msgId = Ids.newMsgId();
        long serverTime = System.currentTimeMillis();

        Msg msg = Msg.newBuilder()
                .setMsgId(msgId)
                .setClientMsgId(req.getClientMsgId())
                .setConvId(convId)
                .setConvType(req.getConvType() == ConvType.CONV_TYPE_UNSPECIFIED ? ConvType.CONV_TYPE_SINGLE : req.getConvType())
                .setSenderId(senderId)
                .setMsgType(req.getMsgType())
                .setContent(req.getContent())
                .setConvSeq(convSeq)
                .setServerTime(serverTime)
                .build();

        // 3) 事务消息：half → 本地事务(落库+水位) → commit；失败/竞态转回查裁决
        boolean committed = producer.sendInTransaction(
                Topics.MSG_EVENT, Topics.TAG_NEW_MSG, convId, msgId, msg.toByteArray(),
                () -> { dao.insert(msg); return true; });

        if (!committed) {
            // 幂等竞态：唯一键拦截了本次插入，回查已 rollback；返回首次成功结果
            ConvTimelineDao.Existing e2 = dao.findByClientMsgId(senderId, req.getClientMsgId());
            if (e2 != null) {
                return respOf(e2);
            }
            throw new BizException(ErrorCodes.SERVER_ERROR, "消息未提交");
        }

        log.info("收单成功 sender={} conv={} convSeq={} msgId={}", senderId, convId, convSeq, msgId);
        return SendMsgResp.newBuilder()
                .setMsgId(msgId).setConvSeq(convSeq).setServerTime(serverTime).build();
    }

    private SendMsgResp respOf(ConvTimelineDao.Existing e) {
        return SendMsgResp.newBuilder()
                .setMsgId(e.msgId()).setConvSeq(e.convSeq()).setServerTime(e.serverTime()).build();
    }
}
