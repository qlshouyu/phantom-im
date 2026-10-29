package im.phantom.media.adapter;

import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import im.phantom.media.app.MediaAppService;
import im.phantom.media.app.MediaDtos.InitUploadRequest;
import im.phantom.media.app.MediaDtos.MediaContent;
import im.phantom.media.app.MediaDtos.MediaDto;
import im.phantom.media.app.MediaDtos.UploadPartDto;
import im.phantom.media.app.MediaDtos.UploadSessionDto;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** 媒体上传、完成和受控下载 API。 */
@RestController
public class MediaController {

    private final MediaAppService media;

    public MediaController(MediaAppService media) {
        this.media = media;
    }

    @PostMapping(ApiRoutes.MEDIA_UPLOAD_INIT)
    public ApiResponse<UploadSessionDto> init(
            @RequestBody InitUploadRequest request,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(media.init(appId, userId, request), requestId);
    }

    @PutMapping(ApiRoutes.MEDIA_UPLOAD_PART)
    public ApiResponse<UploadPartDto> uploadPart(
            @PathVariable("uploadId") String uploadId,
            @PathVariable("partNumber") int partNumber,
            @RequestBody byte[] content,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(media.uploadPart(appId, userId, uploadId, partNumber, content), requestId);
    }

    @PostMapping(ApiRoutes.MEDIA_UPLOAD_COMPLETE)
    public ApiResponse<UploadSessionDto> complete(
            @PathVariable("uploadId") String uploadId,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(media.complete(appId, userId, uploadId), requestId);
    }

    @GetMapping(ApiRoutes.MEDIA)
    public ApiResponse<MediaDto> access(
            @PathVariable("mediaId") String mediaId,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(media.access(appId, userId, mediaId), requestId);
    }

    @GetMapping(ApiRoutes.MEDIA_CONTENT)
    public ResponseEntity<byte[]> content(
            @PathVariable("mediaId") String mediaId,
            @RequestParam("expires") long expiresAt,
            @RequestParam("signature") String signature,
            @RequestParam(value = "variant", required = false, defaultValue = "original") String variant,
            @RequestAttribute(HttpAuthAttributes.APP_ID) String appId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId) {
        MediaContent content = media.content(appId, userId, mediaId, expiresAt, signature, variant);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .header("X-Content-Type-Options", "nosniff");
        if ("FILE".equals(content.mediaType())) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(content.fileName(), StandardCharsets.UTF_8).build().toString());
        }
        return response.body(content.bytes());
    }
}
