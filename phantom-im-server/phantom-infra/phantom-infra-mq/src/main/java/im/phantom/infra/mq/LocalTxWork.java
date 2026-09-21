package im.phantom.infra.mq;

/**
 * 本地事务动作（事务消息 half 后执行）：完成落库并返回是否成功。
 * 返回 true → commit 半消息；false → rollback；抛异常 → UNKNOWN，由 Broker 回查裁决。
 */
@FunctionalInterface
public interface LocalTxWork {
    boolean run() throws Exception;
}
