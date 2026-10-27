package im.phantom.search.app;

import im.phantom.proto.v1.Msg;

final class MessageTextFormat {
    private MessageTextFormat() {}

    static String searchContent(Msg message) {
        return switch (message.getMsgType()) {
            case MSG_TYPE_TEXT -> message.getContent().getText().getText();
            case MSG_TYPE_EMOJI -> message.getContent().getEmoji().getEmojiId();
            case MSG_TYPE_FILE -> message.getContent().getFile().getFileName();
            case MSG_TYPE_LOCATION -> message.getContent().getLocation().getPoiName();
            case MSG_TYPE_CARD -> message.getContent().getCard().getSnapshotName();
            case MSG_TYPE_MERGED -> message.getContent().getMerged().getTitle();
            case MSG_TYPE_SYSTEM -> message.getContent().getSystem().getTemplateId();
            case MSG_TYPE_IMAGE -> "[图片]";
            case MSG_TYPE_VOICE -> "[语音]";
            case MSG_TYPE_VIDEO -> "[视频]";
            default -> "";
        };
    }

    static String digest(Msg message) {
        String text = searchContent(message);
        return text.codePointCount(0, text.length()) <= 120 ? text
                : text.substring(0, text.offsetByCodePoints(0, 120)) + "…";
    }
}
