package im.phantom.conversation.app;

import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgType;

/** 将消息转换为会话列表摘要（CV-03）。 */
public final class MessageDigestFormatter {

    private static final int TEXT_LIMIT = 120;

    private MessageDigestFormatter() {}

    public static String format(Msg msg) {
        if (msg.getRecalled()) {
            return "[撤回] 消息已撤回";
        }
        MsgType type = msg.getMsgType();
        return switch (type) {
            case MSG_TYPE_TEXT -> truncate(msg.getContent().getText().getText());
            case MSG_TYPE_EMOJI -> "[表情]";
            case MSG_TYPE_IMAGE -> "[图片]";
            case MSG_TYPE_VOICE -> "[语音]";
            case MSG_TYPE_VIDEO -> "[视频]";
            case MSG_TYPE_FILE -> "[文件] " + msg.getContent().getFile().getFileName();
            case MSG_TYPE_LOCATION -> "[位置] " + msg.getContent().getLocation().getPoiName();
            case MSG_TYPE_CARD -> "[名片] " + msg.getContent().getCard().getSnapshotName();
            case MSG_TYPE_MERGED -> "[合并转发] " + msg.getContent().getMerged().getTitle();
            case MSG_TYPE_SYSTEM -> "[系统消息] " + msg.getContent().getSystem().getTemplateId();
            case MSG_TYPE_CALL_RECORD -> "[通话记录]";
            default -> "[消息]";
        };
    }

    private static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "[空消息]";
        }
        String trimmed = text.strip();
        return trimmed.codePointCount(0, trimmed.length()) <= TEXT_LIMIT
                ? trimmed : trimmed.substring(0, trimmed.offsetByCodePoints(0, TEXT_LIMIT)) + "…";
    }
}
