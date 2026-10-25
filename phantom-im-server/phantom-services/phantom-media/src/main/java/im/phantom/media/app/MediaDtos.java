package im.phantom.media.app;

import java.util.List;

/** 媒体 HTTP DTO。 */
public final class MediaDtos {
    private MediaDtos() {}

    public record InitUploadRequest(String fileName, long sizeBytes, String sha256, String mimeType,
                                    String mediaType, Integer durationSeconds, Integer width, Integer height,
                                    Boolean burnAfterRead, Integer burnAfterReadTtlSeconds) {}

    public record UploadSessionDto(String uploadId, int partSize, long sizeBytes, int totalParts,
                                   List<Integer> uploadedParts, boolean instant, String mediaId,
                                   String url, String thumbUrl) {
        public UploadSessionDto {
            uploadedParts = uploadedParts == null ? List.of() : List.copyOf(uploadedParts);
        }
    }

    public record UploadPartDto(String uploadId, int partNumber, String etag, long sizeBytes) {}

    public record MediaDto(String mediaId, String mediaType, String mimeType, long sizeBytes,
                           String url, String thumbUrl, Integer durationSeconds, Integer width,
                           Integer height, String fileName, String status) {}

    public record MediaContent(byte[] bytes, String mimeType, String fileName) {}
}
