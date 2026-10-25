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
import im.phantom.proto.v1.TextContent;
import im.phantom.proto.v1.VideoContent;
import im.phantom.proto.v1.VoiceContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageContentValidatorTest {

    @Test
    void acceptsEverySupportedClientMessageType() {
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_TEXT,
                MsgContent.newBuilder().setText(TextContent.newBuilder().setText("你好"))))).doesNotThrowAnyException();
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_EMOJI,
                MsgContent.newBuilder().setEmoji(im.phantom.proto.v1.EmojiContent.newBuilder().setEmojiId("smile")))))
                .doesNotThrowAnyException();
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_IMAGE,
                MsgContent.newBuilder().setImage(ImageContent.newBuilder().setMediaId("m1")
                        .setWidth(100).setHeight(100).setSizeBytes(1024))))).doesNotThrowAnyException();
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_VOICE,
                MsgContent.newBuilder().setVoice(VoiceContent.newBuilder().setMediaId("m1").setDurationS(60)))))
                .doesNotThrowAnyException();
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_VIDEO,
                MsgContent.newBuilder().setVideo(VideoContent.newBuilder().setMediaId("m1")
                        .setDurationS(300).setSizeBytes(100))))).doesNotThrowAnyException();
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_FILE,
                MsgContent.newBuilder().setFile(FileContent.newBuilder().setMediaId("m1")
                        .setFileName("a.pdf").setSizeBytes(100))))).doesNotThrowAnyException();
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_LOCATION,
                MsgContent.newBuilder().setLocation(LocationContent.newBuilder()
                        .setLatitude(31.2).setLongitude(121.5).setPoiName("外滩"))))).doesNotThrowAnyException();
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_CARD,
                MsgContent.newBuilder().setCard(CardContent.newBuilder()
                        .setCardType(ConvType.CONV_TYPE_SINGLE).setTargetId("u2").setSnapshotName("Bob")))))
                .doesNotThrowAnyException();
        assertThatCode(() -> MessageContentValidator.validate(request(MsgType.MSG_TYPE_MERGED,
                MsgContent.newBuilder().setMerged(MergedContent.newBuilder().setTitle("聊天记录")
                        .addMessages(Msg.newBuilder().setMsgType(MsgType.MSG_TYPE_TEXT)))))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchedTypeAndMissingRequiredFields() {
        SendMsgReq imageWithoutMedia = request(MsgType.MSG_TYPE_IMAGE,
                MsgContent.newBuilder().setImage(ImageContent.newBuilder().setWidth(1).setHeight(1).setSizeBytes(1)));
        assertCode(imageWithoutMedia, ErrorCodes.INVALID_PARAM);

        SendMsgReq mismatched = request(MsgType.MSG_TYPE_IMAGE,
                MsgContent.newBuilder().setText(TextContent.newBuilder().setText("x")));
        assertCode(mismatched, ErrorCodes.INVALID_PARAM);
    }

    @Test
    void enforcesPrdSizeAndDurationLimits() {
        assertCode(request(MsgType.MSG_TYPE_VIDEO, MsgContent.newBuilder().setVideo(
                VideoContent.newBuilder().setMediaId("m").setDurationS(301).setSizeBytes(1))),
                ErrorCodes.INVALID_PARAM);
        assertCode(request(MsgType.MSG_TYPE_VIDEO, MsgContent.newBuilder().setVideo(
                VideoContent.newBuilder().setMediaId("m").setDurationS(1)
                        .setSizeBytes(MessageContentValidator.VIDEO_MAX_BYTES + 1))),
                ErrorCodes.MEDIA_TOO_LARGE);
        assertCode(request(MsgType.MSG_TYPE_FILE, MsgContent.newBuilder().setFile(
                FileContent.newBuilder().setMediaId("m").setFileName("big.zip")
                        .setSizeBytes(MessageContentValidator.FILE_MAX_BYTES + 1))),
                ErrorCodes.MEDIA_TOO_LARGE);
    }

    @Test
    void rejectsNestedMergedMessages() {
        Msg nested = Msg.newBuilder().setMsgType(MsgType.MSG_TYPE_MERGED).build();
        SendMsgReq request = request(MsgType.MSG_TYPE_MERGED, MsgContent.newBuilder()
                .setMerged(MergedContent.newBuilder().setTitle("x").addMessages(nested)));
        assertCode(request, ErrorCodes.INVALID_PARAM);
    }

    private SendMsgReq request(MsgType type, MsgContent.Builder content) {
        return SendMsgReq.newBuilder().setClientMsgId("c1").setConvId("si_a_b")
                .setConvType(ConvType.CONV_TYPE_SINGLE).setMsgType(type).setContent(content).build();
    }

    private void assertCode(SendMsgReq request, int code) {
        assertThatThrownBy(() -> MessageContentValidator.validate(request))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(code);
    }
}
