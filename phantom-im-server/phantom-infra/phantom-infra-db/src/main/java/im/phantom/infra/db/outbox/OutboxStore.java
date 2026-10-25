package im.phantom.infra.db.outbox;

import java.util.List;

/** outbox 持久化契约；append 必须与领域状态写入处于同一数据库事务。 */
public interface OutboxStore {

    void append(OutboxRecord record);

    List<OutboxRecord> fetchPending(int limit, long now);

    void markPublished(String outboxId, long publishedAt);

    void markRetry(String outboxId, long nextRetryAt);
}
