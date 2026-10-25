package im.phantom.search.adapter;

import im.phantom.infra.mq.MqConsumers;
import im.phantom.infra.mq.Topics;
import im.phantom.proto.v1.Msg;
import im.phantom.search.app.SearchAppService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** 幂等消费消息与领域事件，维护按用户隔离的搜索文档。 */
@Component
public class SearchEventConsumer implements SmartInitializingSingleton {
    private final MqConsumers consumers;
    private final SearchAppService search;

    public SearchEventConsumer(MqConsumers consumers, SearchAppService search) {
        this.consumers = consumers; this.search = search;
    }

    @Override public void afterSingletonsInstantiated() {
        try {
            consumers.newConsumer("phantom-search-message", Topics.MSG_EVENT, Topics.TAG_NEW_MSG, body -> {
                search.indexMessage(Msg.parseFrom(body)); return true;
            });
            consumers.newConsumer("phantom-search-sync", Topics.SYNC_EVENT, Topics.TAG_STATE_CHANGED, body -> {
                search.onSyncEvent(body); return true;
            });
        } catch (Exception error) {
            throw new IllegalStateException("搜索索引消费者启动失败", error);
        }
    }
}
