package im.phantom.common.risk;

public record ModerationRequest(String appId, String userId, String contentType,
                                String text, String mediaId, String mimeType) {}
