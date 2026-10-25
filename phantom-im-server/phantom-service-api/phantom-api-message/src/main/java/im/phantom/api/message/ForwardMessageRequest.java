package im.phantom.api.message;

import java.util.List;

/** 单条或多选转发目标；merged=true 时 items 生成一条合并消息。 */
public record ForwardMessageRequest(List<String> msgIds, List<String> targetConvIds, Boolean merged) {}
