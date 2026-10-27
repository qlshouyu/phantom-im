package im.phantom.common.gateway;

import im.phantom.proto.v1.Envelope;

/**
 * 网关指令处理 SPI。各业务 module 以 Spring Bean 形式注册自己关心的指令处理器；
 * 网关收集全部 CommandHandler，按 {@link #command()} 分发，网关不依赖任何业务 module。
 */
public interface CommandHandler {

    /** 关心的指令号（proto Command 枚举值，如 Command.COMMAND_SEND_MSG_REQ_VALUE）。 */
    int command();

    /**
     * 处理一帧请求。
     *
     * @param ctx 已认证会话上下文
     * @param req 请求 Envelope（payload 为该指令报文体）
     * @return 响应 Envelope；返回 {@code null} 表示无需回帧（如 MSG_ACK）
     */
    Envelope handle(SessionContext ctx, Envelope req) throws Exception;
}
