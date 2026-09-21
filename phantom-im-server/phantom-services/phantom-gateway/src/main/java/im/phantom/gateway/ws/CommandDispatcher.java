package im.phantom.gateway.ws;

import im.phantom.common.gateway.CommandHandler;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指令分发器：聚合全部业务 module 注册的 CommandHandler（SPI），按指令号路由。
 * 网关不依赖任何业务 module，只依赖 common 中的 CommandHandler 契约。
 */
@Component
public class CommandDispatcher {

    private final Map<Integer, CommandHandler> handlers = new HashMap<>();

    public CommandDispatcher(List<CommandHandler> beans) {
        for (CommandHandler h : beans) {
            handlers.put(h.command(), h);
        }
    }

    public CommandHandler find(int command) {
        return handlers.get(command);
    }
}
