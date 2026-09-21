package im.phantom.infra.mq;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事务消息生产者（single-chat-messaging spec，MQ 架构 §2），基于 RocketMQ 5.x gRPC 客户端。
 *
 * <p>流程：beginTransaction → send(half) → 执行本地事务(落库) → 成功 commit / 失败 rollback。
 * 悬挂半消息由 {@link TransactionChecker} 按消息属性 phantomMsgId 回查裁决。
 */
public class TxMqProducer {

    private static final Logger log = LoggerFactory.getLogger(TxMqProducer.class);
    static final String PROP_MSG_ID = "phantomMsgId";

    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final MqProperties props;
    private final TransactionChecker checker;
    private Producer producer;

    public TxMqProducer(MqProperties props, TransactionChecker checker) {
        this.props = props;
        this.checker = checker;
    }

    public void start() throws ClientException {
        ClientConfiguration cc = ClientConfiguration.newBuilder()
                .setEndpoints(props.getEndpoints())
                .build();
        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(cc)
                .setTopics(Topics.MSG_EVENT)
                .setTransactionChecker(view -> {
                    String msgId = view.getProperties().get(PROP_MSG_ID);
                    boolean committed = checker.committed(msgId);
                    log.info("事务回查 msgId={} committed={}", msgId, committed);
                    return committed ? TransactionResolution.COMMIT : TransactionResolution.ROLLBACK;
                })
                .build();
        log.info("TxMqProducer 启动，endpoints={}", props.getEndpoints());
    }

    public void stop() throws Exception {
        if (producer != null) {
            producer.close();
        }
    }

    /**
     * 发送事务消息：先发 half，再执行本地事务，据结果 commit/rollback。
     * @return 是否 commit
     */
    public boolean sendInTransaction(String topic, String tag, String shardingKey,
                                     String msgId, byte[] body, LocalTxWork work) throws ClientException {
        Transaction transaction = producer.beginTransaction();
        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                .setTag(tag)
                .setKeys(shardingKey)
                .addProperty(PROP_MSG_ID, msgId)
                .setBody(body)
                .build();
        producer.send(message, transaction);   // 发送 half
        boolean ok;
        try {
            ok = work.run();
        } catch (Exception e) {
            safeRollback(transaction);
            log.warn("本地事务异常，rollback 半消息 msgId={}: {}", msgId, e.toString());
            return false;
        }
        try {
            if (ok) {
                transaction.commit();
            } else {
                transaction.rollback();
            }
        } catch (ClientException ce) {
            // commit/rollback 网络异常：交由 Broker 回查兜底（checker 已能判定）
            log.warn("事务 commit/rollback 异常，转回查 msgId={}: {}", msgId, ce.toString());
        }
        return ok;
    }

    private void safeRollback(Transaction t) {
        try {
            t.rollback();
        } catch (Exception ignore) {
            // 忽略；回查兜底
        }
    }
}
