package im.phantom.message.app;

import im.phantom.message.infra.ConvTimelineDao;
import im.phantom.message.infra.MessageOutboxDao;
import im.phantom.proto.v1.Msg;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 消息本地事务边界：Timeline、扩展、水位与可选的新消息 Outbox 原子提交。 */
@Service
public class MessagePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceService.class);

    private final ConvTimelineDao timelineDao;
    private final MessageOutboxDao outboxDao;
    private final MessageEventOutboxRelay relay;

    public MessagePersistenceService(ConvTimelineDao timelineDao, MessageOutboxDao outboxDao,
                                     MessageEventOutboxRelay relay) {
        this.timelineDao = timelineDao;
        this.outboxDao = outboxDao;
        this.relay = relay;
    }

    @Transactional
    public void persistTransactionMq(Msg message) {
        timelineDao.insert(message);
    }

    @Transactional
    public void persistWithOutbox(Msg message) {
        timelineDao.insert(message);
        outboxDao.appendMessageEvent(message);
        log.debug("消息与 Outbox 已加入本地事务 msgId={} clientMsgId={}", message.getMsgId(), message.getClientMsgId());
        wakeAfterCommit();
    }

    private void wakeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            relay.wakeup();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.debug("消息本地事务已提交，唤醒 Outbox relay");
                relay.wakeup();
            }
        });
    }
}
