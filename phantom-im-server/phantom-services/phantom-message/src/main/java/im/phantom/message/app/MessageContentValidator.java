package im.phantom.message.app;

import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.proto.v1.CardContent;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.FileContent;
import im.phantom.proto.v1.ImageContent;
import im.phantom.proto.v1.LocationContent;
import im.phantom.proto.v1.MergedContent;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.SendMsgReq;
import im.phantom.proto.v1.VideoContent;
import im.phantom.proto.v1.VoiceContent;

/** 通用消息的结构、类型和 PRD 上限校验。 */
final class MessageContentValidator {

    static final int TEXT_MAX_CODE_POINTS = 5_000;
    static final int VOICE_MAX_SECONDS = 60;
    static final int VIDEO_MAX_SECONDS = 300;
    static final long VIDEO_MAX_BYTES = 100L * 1024 * 1024;
    static final long FILE_MAX_BYTES = 500L * 1024 * 1024;
    static final int MERGED_MAX_MESSAGES = 100;

    private MessageContentValidator() {}

    static void validate(SendMsgReq request) {
        if (request.getClientMsgId() == null || request.getClientMsgId().isBlank()) {
            throw invalid("client_msg_id 不能为空");
        }
        if (request.getConvId() == null || request.getConvId().isBlank()) {
            throw invalid("conv_id 不能为空");
        }
        if (request.getMsgType() == MsgType.MSG_TYPE_UNSPECIFIED) {
            throw invalid("消息类型不能为空");
        }
        if (!request.hasContent()) {
            throw invalid("消息内容不能为空");
        }
        MsgContent content = request.getContent();
        if (content.getContentCase().getNumber() != request.getMsgTypeValue()) {
            throw invalid("消息类型与内容不匹配");
        }
        switch (request.getMsgType()) {
            case MSG_TYPE_TEXT -> validateText(content.getText().getText());
            case MSG_TYPE_EMOJI -> requireText(content.getEmoji().getEmojiId(), "emoji_id", 128);
            case MSG_TYPE_IMAGE -> validateImage(content.getImage());
            case MSG_TYPE_VOICE -> validateVoice(content.getVoice());
            case MSG_TYPE_VIDEO -> validateVideo(content.getVideo());
            case MSG_TYPE_FILE -> validateFile(content.getFile());
            case MSG_TYPE_LOCATION -> validateLocation(content.getLocation());
            case MSG_TYPE_CARD -> validateCard(content.getCard());
            case MSG_TYPE_MERGED -> validateMerged(content.getMerged());
            case MSG_TYPE_SYSTEM -> requireText(content.getSystem().getTemplateId(), "template_id", 128);
            default -> throw invalid("暂不支持该消息类型");
        }
    }

    private static void validateText(String text) {
        if (text == null || text.isEmpty()) {
            throw invalid("文本消息不能为空");
        }
        if (text.codePointCount(0, text.length()) > TEXT_MAX_CODE_POINTS) {
            throw new BizException(ErrorCodes.MSG_TOO_LONG, "文本超过 5000 字");
        }
    }

    private static void validateImage(ImageContent image) {
        requireText(image.getMediaId(), "media_id", 64);
        if (image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw invalid("图片宽高必须大于 0");
        }
        if (image.getSizeBytes() <= 0) {
            throw invalid("图片大小必须大于 0");
        }
    }

    private static void validateVoice(VoiceContent voice) {
        requireText(voice.getMediaId(), "media_id", 64);
        if (voice.getDurationS() <= 0 || voice.getDurationS() > VOICE_MAX_SECONDS) {
            throw invalid("语音时长必须在 1-60 秒之间");
        }
    }

    private static void validateVideo(VideoContent video) {
        requireText(video.getMediaId(), "media_id", 64);
        if (video.getDurationS() <= 0 || video.getDurationS() > VIDEO_MAX_SECONDS) {
            throw invalid("视频时长必须在 1-300 秒之间");
        }
        if (video.getSizeBytes() <= 0) {
            throw invalid("视频大小必须大于 0");
        }
        if (video.getSizeBytes() > VIDEO_MAX_BYTES) {
            throw new BizException(ErrorCodes.MEDIA_TOO_LARGE, "视频超过 100MB");
        }
    }

    private static void validateFile(FileContent file) {
        requireText(file.getMediaId(), "media_id", 64);
        requireText(file.getFileName(), "file_name", 512);
        if (file.getSizeBytes() <= 0) {
            throw invalid("文件大小必须大于 0");
        }
        if (file.getSizeBytes() > FILE_MAX_BYTES) {
            throw new BizException(ErrorCodes.MEDIA_TOO_LARGE, "文件超过 500MB");
        }
    }

    private static void validateLocation(LocationContent location) {
        if (location.getLatitude() < -90 || location.getLatitude() > 90
                || location.getLongitude() < -180 || location.getLongitude() > 180) {
            throw invalid("经纬度非法");
        }
        requireText(location.getPoiName(), "poi_name", 128);
    }

    private static void validateCard(CardContent card) {
        if (card.getCardType() != ConvType.CONV_TYPE_SINGLE
                && card.getCardType() != ConvType.CONV_TYPE_GROUP) {
            throw invalid("名片类型非法");
        }
        requireText(card.getTargetId(), "target_id", 128);
        requireText(card.getSnapshotName(), "snapshot_name", 128);
    }

    private static void validateMerged(MergedContent merged) {
        requireText(merged.getTitle(), "title", 128);
        if (merged.getMessagesCount() == 0 || merged.getMessagesCount() > MERGED_MAX_MESSAGES) {
            throw invalid("合并消息必须包含 1-100 条消息");
        }
        for (Msg snapshot : merged.getMessagesList()) {
            if (snapshot.getMsgType() == MsgType.MSG_TYPE_MERGED) {
                throw invalid("合并消息不可嵌套");
            }
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " 不能为空");
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw invalid(field + " 超过长度限制");
        }
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCodes.INVALID_PARAM, message);
    }
}
