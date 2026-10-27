package im.phantom.push;

import im.phantom.api.route.GatewayDeliveryService;
import im.phantom.api.route.OnlineDeliveryService;
import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.common.Envelopes;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.DeviceType;
import im.phantom.proto.v1.Envelope;
import im.phantom.proto.v1.MsgPush;
import im.phantom.infra.redis.AckCursorStore;
import im.phantom.infra.observability.PlatformMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Timer;

/**
 * 在线投递本地实现（message-fanout-delivery spec）：查路由表 → 对每个在线连接下发 MsgPush。
 * 无在线连接返回 0（留待 SyncReq 补齐）。
 */
@Service
public class OnlineDeliveryServiceImpl implements OnlineDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(OnlineDeliveryServiceImpl.class);

    private final RouteTableService routeTable;
    private final GatewayDeliveryService gateway;
    private final ObjectProvider<AckCursorStore> ackCursor;
    private final ScheduledExecutorService scheduler;
    private final PlatformMetrics metrics;
    private final ExecutorService devicePushExecutor;

    @Autowired
    public OnlineDeliveryServiceImpl(RouteTableService routeTable, GatewayDeliveryService gateway,
                                     ObjectProvider<AckCursorStore> ackCursor,
                                     @Qualifier("pushScheduler") ScheduledExecutorService pushScheduler,
                                     PlatformMetrics metrics,
                                     @Qualifier("devicePushExecutor") ExecutorService devicePushExecutor) {
        this.routeTable = routeTable;
        this.gateway = gateway;
        this.ackCursor = ackCursor;
        this.scheduler = pushScheduler;
        this.metrics = metrics;
        this.devicePushExecutor = devicePushExecutor;
    }

    @Override
    public int deliver(String receiverId, MsgPush push) {
        int sent = deliverEnvelope(receiverId, Envelopes.push(Command.COMMAND_MSG_PUSH, push));
        if (sent > 0 && metrics != null) {
            metrics.recordMessageStage("delivery_e2e", "online",
                    Math.max(0, System.currentTimeMillis() - push.getMsg().getServerTime()) * 1_000_000L);
        }
        if (sent > 0) {
            scheduler.schedule(() -> retryUnacked(receiverId, push),
                    5, TimeUnit.SECONDS);
        }
        return sent;
    }

    @Override
    public int deliverEnvelope(String receiverId, Envelope envelope) {
        Timer.Sample routeStage = metrics == null ? null : metrics.startMessageStage();
        List<RouteEntry> entries = routeTable.query(receiverId);
        if (routeStage != null) {
            metrics.recordMessageStage(routeStage, "route_query", "success");
        }
        if (entries.isEmpty()) {
            return 0;
        }
        int sent = 0;
        List<Future<Boolean>> sends = new ArrayList<>(entries.size());
        for (RouteEntry entry : entries) {
            sends.add(devicePushExecutor.submit(() -> {
                long started = System.nanoTime();
                boolean delivered = gateway.send(entry.gatewayId(), entry.connId(), envelope);
                if (metrics != null) {
                    metrics.recordWebSocketPush(deviceTypeName(entry.deviceType()),
                            delivered ? "success" : "failure", System.nanoTime() - started);
                }
                return delivered;
            }));
        }
        for (Future<Boolean> send : sends) {
            try {
                if (send.get()) {
                    sent++;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException error) {
                log.warn("在线设备发送失败: {}", error.getCause().toString());
            }
        }
        for (Future<Boolean> send : sends) {
            if (!send.isDone()) {
                send.cancel(true);
            }
        }
        return sent;
    }

    private void retryUnacked(String receiverId, MsgPush push) {
        AckCursorStore cursors = ackCursor.getIfAvailable();
        if (cursors == null) {
            return;
        }
        Envelope envelope = Envelopes.push(Command.COMMAND_MSG_PUSH, push);
        for (RouteEntry entry : routeTable.query(receiverId)) {
            if (cursors.get(receiverId, entry.deviceId()) < push.getUserSeq()) {
                gateway.send(entry.gatewayId(), entry.connId(), envelope);
            }
        }
    }

    private String deviceTypeName(int deviceType) {
        DeviceType type = DeviceType.forNumber(deviceType);
        return type == null ? "UNSPECIFIED" : type.name();
    }
}
