package im.phantom.gateway.ws;

import im.phantom.proto.v1.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关本地连接表（进程内存）：connId → WebSocket 连接。持有真正的连接句柄，
 * Redis 路由表只记录 connId 归属哪个网关（MQ 架构 §6）。
 */
@Component
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Map<String, ConcurrentWebSocketSessionDecorator> byConnId = new ConcurrentHashMap<>();

    public void register(String connId, WebSocketSession raw) {
        // 并发安全发送包装（推送线程与握手线程可能并发写同一连接）
        byConnId.put(connId, new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024));
    }

    public void remove(String connId) {
        byConnId.remove(connId);
    }

    /** 向指定连接发送一帧；连接不存在或发送失败返回 false。 */
    public boolean send(String connId, Envelope env) {
        ConcurrentWebSocketSessionDecorator s = byConnId.get(connId);
        if (s == null || !s.isOpen()) {
            return false;
        }
        try {
            s.sendMessage(new BinaryMessage(env.toByteArray()));
            return true;
        } catch (Exception e) {
            log.warn("下发失败 connId={}: {}", connId, e.toString());
            return false;
        }
    }

    public void close(String connId) {
        ConcurrentWebSocketSessionDecorator s = byConnId.get(connId);
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignore) {
                // 忽略关闭异常
            }
        }
    }
}
