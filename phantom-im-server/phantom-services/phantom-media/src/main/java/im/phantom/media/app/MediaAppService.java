package im.phantom.media.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.media.MediaProperties;
import im.phantom.media.app.MediaDtos.InitUploadRequest;
import im.phantom.media.app.MediaDtos.MediaContent;
import im.phantom.media.app.MediaDtos.MediaDto;
import im.phantom.media.app.MediaDtos.UploadPartDto;
import im.phantom.media.app.MediaDtos.UploadSessionDto;
import im.phantom.media.infra.JdbcMediaRepository;
import im.phantom.media.infra.JdbcMediaRepository.MediaRecord;
import im.phantom.media.infra.JdbcMediaRepository.PartRecord;
import im.phantom.media.infra.JdbcMediaRepository.UploadRecord;
import im.phantom.media.spi.MediaStorage;
import im.phantom.media.ffmpeg.FfmpegMediaProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 分片上传、秒传、完整性校验、访问签名和过期清理。 */
@Service
public class MediaAppService {

    private static final long MB = 1024L * 1024L;
    private static final long FILE_MAX = 500 * MB;
    private static final long VIDEO_MAX = 100 * MB;
    private static final int VOICE_MAX_SECONDS = 60;
    private static final Set<String> MEDIA_TYPES = Set.of("IMAGE", "VOICE", "VIDEO", "FILE");
    private static final Set<String> FILE_MIME_TYPES = Set.of(
            "application/pdf", "application/zip", "application/x-7z-compressed",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "text/plain", "application/octet-stream");

    private final JdbcMediaRepository repository;
    private final MediaStorage storage;
    private final MediaProperties properties;
    private final MediaAccessTokenService tokens;
    private final ObjectMapper objectMapper;
    private final FfmpegMediaProbe mediaProbe;
    private final ImageMediaProcessor imageProcessor;

    @Autowired
    public MediaAppService(JdbcMediaRepository repository, MediaStorage storage, MediaProperties properties,
                           MediaAccessTokenService tokens, ObjectMapper objectMapper,
                           FfmpegMediaProbe mediaProbe, ImageMediaProcessor imageProcessor) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.mediaProbe = mediaProbe;
        this.imageProcessor = imageProcessor;
    }

    MediaAppService(JdbcMediaRepository repository, MediaStorage storage, MediaProperties properties,
                    MediaAccessTokenService tokens, ObjectMapper objectMapper) {
        this(repository, storage, properties, tokens, objectMapper, null, new ImageMediaProcessor());
    }

    MediaAppService(JdbcMediaRepository repository, MediaStorage storage, MediaProperties properties,
                    MediaAccessTokenService tokens, ObjectMapper objectMapper, ImageMediaProcessor imageProcessor) {
        this(repository, storage, properties, tokens, objectMapper, null, imageProcessor);
    }

    @Transactional
    public UploadSessionDto init(String appId, String userId, InitUploadRequest request) {
        ValidatedUpload upload = validateInit(request);
        String sha = normalizeSha(request.sha256());
        if (!upload.burnAfterRead()) {
            var reusable = repository.findReusable(appId, userId, sha)
                    .filter(media -> media.sizeBytes() == request.sizeBytes()
                            && media.mimeType().equalsIgnoreCase(upload.mimeType()));
            if (reusable.isPresent()) {
                MediaRecord media = reusable.get();
                return new UploadSessionDto(media.mediaId(), 0, media.sizeBytes(), 0, List.of(),
                        true, media.mediaId(), url(media.mediaId(), userId, appId),
                        media.thumbKey() == null ? null : url(media.mediaId(), userId, appId, true));
            }
        }
        long now = System.currentTimeMillis();
        UploadRecord existing = repository.findResumable(appId, userId, sha).orElse(null);
        if (existing != null) {
            return session(existing, repository.uploadedParts(existing.uploadId()), false, null, null, null);
        }
        String uploadId = Ids.newUploadId();
        int partSize = properties.getPartSizeBytes();
        int totalParts = (int) ((request.sizeBytes() + partSize - 1) / partSize);
        UploadRecord created = new UploadRecord(uploadId, appId, userId, upload.fileName(), sha,
                upload.mimeType(), request.sizeBytes(), partSize, "INIT", null, upload.metadataJson(),
                upload.burnAfterRead(), upload.burnTtlSeconds(), now, now,
                now + properties.getUploadTtlSeconds() * 1000L);
        repository.insertUpload(created);
        return session(created, List.of(), false, null, null, null);
    }

    @Transactional
    public UploadPartDto uploadPart(String appId, String userId, String uploadId, int partNumber,
                                    byte[] bytes) {
        UploadRecord upload = requireUpload(uploadId, appId, userId);
        if (!"INIT".equals(upload.status()) && !"UPLOADING".equals(upload.status())) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "上传会话已结束");
        }
        if (upload.expiresAt() < System.currentTimeMillis()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "上传会话已过期");
        }
        int totalParts = totalParts(upload);
        if (partNumber < 1 || partNumber > totalParts) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "分片编号越界");
        }
        int expectedSize = partNumber == totalParts
                ? (int) (upload.sizeBytes() - (long) (totalParts - 1) * upload.partSize())
                : upload.partSize();
        if (bytes == null || bytes.length != expectedSize) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "分片大小不符合上传会话约定");
        }
        String storageKey = "upload/" + uploadId + "/" + partNumber;
        storage.write(storageKey, bytes);
        String etag = sha256(bytes);
        repository.upsertPart(uploadId, partNumber, etag, bytes.length, System.currentTimeMillis());
        repository.touchUpload(uploadId, "UPLOADING", System.currentTimeMillis());
        return new UploadPartDto(uploadId, partNumber, etag, bytes.length);
    }

    @Transactional
    public UploadSessionDto complete(String appId, String userId, String uploadId) {
        UploadRecord upload = requireUpload(uploadId, appId, userId);
        if ("COMPLETED".equals(upload.status())) {
            MediaRecord existing = repository.findMedia(upload.mediaId())
                    .orElseThrow(() -> new BizException(ErrorCodes.SERVER_ERROR, "上传结果缺失"));
            return completed(existing, userId, appId);
        }
        List<PartRecord> parts = repository.listParts(uploadId);
        int expectedParts = totalParts(upload);
        if (parts.size() != expectedParts) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "分片未上传完整");
        }
        ByteArrayOutputStream assembled = new ByteArrayOutputStream((int) upload.sizeBytes());
        for (int i = 1; i <= expectedParts; i++) {
            PartRecord part = parts.get(i - 1);
            if (part.partNumber() != i) {
                throw new BizException(ErrorCodes.INVALID_PARAM, "分片序号不连续");
            }
            assembled.writeBytes(storage.read("upload/" + uploadId + "/" + i));
        }
        byte[] content = assembled.toByteArray();
        if (content.length != upload.sizeBytes() || !sha256(content).equals(upload.sha256())) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "文件摘要不匹配");
        }
        String mediaId = Ids.newMediaId();
        String extension = extension(upload.fileName());
        String storageKey = "app/" + appId + "/media/" + mediaId + extension;
        storage.write(storageKey, content);
        String thumbKey = thumbnail(storageKey, upload, content);
        String metadataJson = enrichMetadata(upload, content, extension);
        long now = System.currentTimeMillis();
        long expiresAt = now + (upload.burnAfterRead() ? Math.max(1, upload.burnTtlSeconds())
                : properties.getUnboundMediaTtlSeconds()) * 1000L;
        MediaRecord media = new MediaRecord(mediaId, appId, userId, storageKey, upload.sha256(),
                upload.mimeType(), upload.sizeBytes(), mediaType(upload.mimeType()), thumbKey,
                metadataJson, upload.burnAfterRead(), "READY", now, expiresAt);
        repository.insertMedia(media);
        repository.completeUpload(uploadId, mediaId, now);
        for (PartRecord part : parts) {
            storage.delete("upload/" + uploadId + "/" + part.partNumber());
        }
        return completed(media, userId, appId);
    }

    public MediaDto access(String appId, String userId, String mediaId) {
        MediaRecord media = requireAccessible(appId, userId, mediaId);
        return toDto(media, userId, appId);
    }

    public MediaContent content(String appId, String userId, String mediaId, long expiresAt,
                                String signature, String variant) {
        String selected = variant == null || variant.isBlank() ? "original" : variant;
        if (!"original".equals(selected) && !"thumb".equals(selected)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "不支持的媒体变体");
        }
        if (!tokens.verify(mediaId, selected, userId, appId, expiresAt, signature)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "媒体访问地址无效或已过期");
        }
        MediaRecord media = requireAccessible(appId, userId, mediaId);
        if (!"READY".equals(media.status())) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "媒体已不可用");
        }
        if ("thumb".equals(selected)) {
            if (media.thumbKey() == null) {
                throw new BizException(ErrorCodes.INVALID_PARAM, "媒体缩略图不存在");
            }
            return new MediaContent(storage.read(media.thumbKey()), "image/jpeg", "thumbnail.jpg");
        }
        return new MediaContent(storage.read(media.storageKey()), media.mimeType(), media.mimeType());
    }

    @Scheduled(fixedDelayString = "${phantom.media.cleanup-interval-ms:60000}")
    public void cleanup() {
        cleanupOnce();
    }

    @Transactional
    public int cleanupOnce() {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        for (UploadRecord upload : repository.expiredUploads(now, 100)) {
            for (PartRecord part : repository.listPartsForCleanup(upload.uploadId())) {
                storage.delete("upload/" + upload.uploadId() + "/" + part.partNumber());
            }
            repository.expireUpload(upload.uploadId(), now);
            cleaned++;
        }
        for (MediaRecord media : repository.cleanupCandidates(now, 100)) {
            storage.delete(media.storageKey());
            if (media.thumbKey() != null) {
                storage.delete(media.thumbKey());
            }
            repository.markDeleted(media.mediaId());
            cleaned++;
        }
        return cleaned;
    }

    private UploadRecord requireUpload(String uploadId, String appId, String userId) {
        UploadRecord upload = repository.findUpload(uploadId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "上传会话不存在"));
        if (!upload.appId().equals(appId) || !upload.ownerId().equals(userId)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "无权访问上传会话");
        }
        return upload;
    }

    private MediaRecord requireAccessible(String appId, String userId, String mediaId) {
        MediaRecord media = repository.findMedia(mediaId)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "媒体不存在"));
        if (!media.appId().equals(appId)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "媒体不属于当前应用");
        }
        if (!media.ownerId().equals(userId) && !repository.canAccessMessageMedia(mediaId, userId)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "无权访问该媒体");
        }
        return media;
    }

    private ValidatedUpload validateInit(InitUploadRequest request) {
        if (request == null || request.fileName() == null || request.fileName().isBlank()
                || request.sizeBytes() <= 0 || request.mimeType() == null || request.mimeType().isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "上传参数不完整");
        }
        String mediaType = request.mediaType() == null ? "FILE" : request.mediaType().toUpperCase(Locale.ROOT);
        if (!MEDIA_TYPES.contains(mediaType)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "不支持的媒体类型");
        }
        String mime = request.mimeType().toLowerCase(Locale.ROOT);
        if (request.sizeBytes() > FILE_MAX) {
            throw new BizException(ErrorCodes.MEDIA_TOO_LARGE, "文件超过 500MB");
        }
        if ("VIDEO".equals(mediaType) && request.sizeBytes() > VIDEO_MAX) {
            throw new BizException(ErrorCodes.MEDIA_TOO_LARGE, "视频超过 100MB");
        }
        if ("VOICE".equals(mediaType) && (request.durationSeconds() == null
                || request.durationSeconds() <= 0 || request.durationSeconds() > VOICE_MAX_SECONDS)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "语音时长必须在 1-60 秒之间");
        }
        if ("IMAGE".equals(mediaType) && !mime.startsWith("image/")) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "图片 MIME 非法");
        }
        if ("VOICE".equals(mediaType) && !mime.startsWith("audio/")) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "语音 MIME 非法");
        }
        if ("VIDEO".equals(mediaType) && !mime.startsWith("video/")) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "视频 MIME 非法");
        }
        if ("FILE".equals(mediaType) && !(FILE_MIME_TYPES.contains(mime) || mime.startsWith("text/"))) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "文件 MIME 非法");
        }
        Map<String, Object> metadata = Map.of(
                "fileName", request.fileName(),
                "extension", extension(request.fileName()),
                "durationSeconds", request.durationSeconds() == null ? 0 : request.durationSeconds(),
                "width", request.width() == null ? 0 : request.width(),
                "height", request.height() == null ? 0 : request.height(),
                "sha256", normalizeSha(request.sha256()));
        return new ValidatedUpload(request.fileName(), mime, mediaType,
                Boolean.TRUE.equals(request.burnAfterRead()),
                request.burnAfterReadTtlSeconds() == null ? 0 : request.burnAfterReadTtlSeconds(),
                json(metadata));
    }

    private UploadSessionDto session(UploadRecord upload, List<Integer> uploaded, boolean instant,
                                     String mediaId, String url, String thumbUrl) {
        return new UploadSessionDto(upload.uploadId(), upload.partSize(), upload.sizeBytes(),
                totalParts(upload), uploaded, instant, mediaId, url, thumbUrl);
    }

    private UploadSessionDto completed(MediaRecord media, String userId, String appId) {
        return new UploadSessionDto(null, 0, media.sizeBytes(), 0, List.of(), false,
                media.mediaId(), url(media.mediaId(), userId, appId),
                media.thumbKey() == null ? null : url(media.mediaId(), userId, appId, true));
    }

    private MediaDto toDto(MediaRecord media, String userId, String appId) {
        Map<?, ?> metadata = readMetadata(media.metadataJson());
        Object fileName = metadata.get("fileName");
        return new MediaDto(media.mediaId(), media.mediaType(), media.mimeType(), media.sizeBytes(),
                url(media.mediaId(), userId, appId),
                media.thumbKey() == null ? null : url(media.mediaId(), userId, appId, true),
                integer(metadata.get("durationSeconds")), integer(metadata.get("width")),
                integer(metadata.get("height")), fileName == null ? "" : fileName.toString(),
                media.status());
    }

    private String thumbnail(String sourceKey, UploadRecord upload, byte[] content) {
        if (upload.mimeType().startsWith("video/") && mediaProbe != null) {
            return mediaProbe.videoThumbnail(content, extension(upload.fileName()))
                    .map(bytes -> {
                        String key = sourceKey + ".thumb.jpg";
                        storage.write(key, bytes);
                        return key;
                    }).orElse(null);
        }
        if (!upload.mimeType().startsWith("image/")) {
            return null;
        }
        return imageProcessor.thumbnail(content, 320).map(bytes -> {
            String key = sourceKey + ".thumb.jpg";
            storage.write(key, bytes);
            return key;
        }).orElse(null);
    }

    private String enrichMetadata(UploadRecord upload, byte[] content, String extension) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        readMetadata(upload.metadataJson()).forEach((key, value) -> {
            if (key instanceof String text) metadata.put(text, value);
        });
        metadata.put("sizeBytes", upload.sizeBytes());
        metadata.put("mimeType", upload.mimeType());
        if (upload.mimeType().startsWith("image/")) {
            imageProcessor.dimensions(content).ifPresentOrElse(dimensions -> {
                metadata.put("width", dimensions[0]);
                metadata.put("height", dimensions[1]);
            }, () -> {
                // 缩略图生成失败时仍保留原始媒体；无法解析的图片不信任客户端宽高。
                metadata.remove("width");
                metadata.remove("height");
            });
            return json(metadata);
        }
        if (mediaProbe != null && (upload.mimeType().startsWith("video/")
                || upload.mimeType().startsWith("audio/"))) {
            mediaProbe.probe(content, extension).ifPresent(probe -> {
                if (probe.width() != null) metadata.put("width", probe.width());
                if (probe.height() != null) metadata.put("height", probe.height());
                if (probe.durationSeconds() != null) metadata.put("durationSeconds", probe.durationSeconds());
                if (probe.codec() != null) metadata.put("codec", probe.codec());
            });
        }
        return json(metadata);
    }

    private String url(String mediaId, String userId, String appId) {
        return url(mediaId, userId, appId, false);
    }

    private String url(String mediaId, String userId, String appId, boolean thumbnail) {
        long expiresAt = System.currentTimeMillis() + properties.getAccessUrlTtlSeconds() * 1000L;
        String variant = thumbnail ? "thumb" : "original";
        String signature = tokens.sign(mediaId, variant, userId, appId, expiresAt);
        return "/media/" + mediaId + "/content?expires=" + expiresAt + "&signature=" + signature
                + "&variant=" + variant;
    }

    private int totalParts(UploadRecord upload) {
        return Math.max(1, (int) ((upload.sizeBytes() + upload.partSize() - 1) / upload.partSize()));
    }

    private String normalizeSha(String sha) {
        if (sha == null || !sha.matches("[0-9a-fA-F]{64}")) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "sha256 必须为 64 位十六进制");
        }
        return sha.toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 计算失败", error);
        }
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }

    private String mediaType(String mime) {
        if (mime.startsWith("image/")) return "IMAGE";
        if (mime.startsWith("audio/")) return "VOICE";
        if (mime.startsWith("video/")) return "VIDEO";
        return "FILE";
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("媒体元数据序列化失败", error);
        }
    }

    private Map<?, ?> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception error) {
            return Map.of();
        }
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private record ValidatedUpload(String fileName, String mimeType, String mediaType,
                                   boolean burnAfterRead, int burnTtlSeconds, String metadataJson) {}
}
