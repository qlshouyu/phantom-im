package im.phantom.risk.spi;

/** 待审核内容。 */
public record ModerationRequest(
        String appId,
        String userId,
        String contentType,
        String text,
        String mediaId,
        String mimeType
) {}
