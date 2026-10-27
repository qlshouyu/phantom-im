package im.phantom.gateway.ws;

import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.common.Envelopes;
import im.phantom.common.gateway.SessionContext;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.PresenceInfo;
import im.phantom.proto.v1.PresencePush;
import im.phantom.proto.v1.PresenceStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 在线状态订阅（进程内，单网关 MVP）；状态本身不落库。 */
@Component
public class PresenceRegistry {

    private final RouteTableService routes;
    private final ConnectionRegistry connections;
    private final Map<String, Set<String>> watchedByConn = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscribersByUser = new ConcurrentHashMap<>();

    public PresenceRegistry(RouteTableService routes, ConnectionRegistry connections) {
        this.routes = routes;
        this.connections = connections;
    }

    public void subscribe(SessionContext context, Set<String> userIds) {
        remove(context.connId());
        watchedByConn.put(context.connId(), ConcurrentHashMap.newKeySet());
        for (String userId : userIds) {
            watchedByConn.get(context.connId()).add(userId);
            subscribersByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(context.connId());
        }
    }

    public void unsubscribe(SessionContext context, Set<String> userIds) {
        Set<String> watched = watchedByConn.get(context.connId());
        if (watched == null) {
            return;
        }
        for (String userId : userIds) {
            watched.remove(userId);
            Set<String> subscribers = subscribersByUser.get(userId);
            if (subscribers != null) {
                subscribers.remove(context.connId());
            }
        }
    }

    public PresenceInfo snapshot(String userId) {
        boolean online = !routes.query(userId).isEmpty();
        return PresenceInfo.newBuilder().setUserId(userId)
                .setStatus(online ? PresenceStatus.PRESENCE_STATUS_ONLINE
                        : PresenceStatus.PRESENCE_STATUS_OFFLINE)
                .build();
    }

    public void userOnline(SessionContext context) {
        broadcast(context.userId(), PresenceStatus.PRESENCE_STATUS_ONLINE);
    }

    public void userOffline(SessionContext context) {
        if (routes.query(context.userId()).isEmpty()) {
            broadcast(context.userId(), PresenceStatus.PRESENCE_STATUS_OFFLINE);
        }
    }

    public void remove(String connId) {
        Set<String> watched = watchedByConn.remove(connId);
        if (watched == null) {
            return;
        }
        for (String userId : watched) {
            Set<String> subscribers = subscribersByUser.get(userId);
            if (subscribers != null) {
                subscribers.remove(connId);
            }
        }
    }

    private void broadcast(String userId, PresenceStatus status) {
        PresenceInfo info = PresenceInfo.newBuilder().setUserId(userId).setStatus(status).build();
        var envelope = Envelopes.push(Command.COMMAND_PRESENCE_PUSH,
                PresencePush.newBuilder().setPresence(info).build());
        for (String connId : subscribersByUser.getOrDefault(userId, Set.of())) {
            connections.send(connId, envelope);
        }
    }
}
