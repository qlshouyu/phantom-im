package im.phantom.media.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.media.MediaProperties;
import im.phantom.media.app.MediaDtos.InitUploadRequest;
import im.phantom.media.infra.JdbcMediaRepository;
import im.phantom.media.infra.JdbcMediaRepository.MediaRecord;
import im.phantom.media.infra.JdbcMediaRepository.PartRecord;
import im.phantom.media.infra.JdbcMediaRepository.UploadRecord;
import im.phantom.media.spi.MediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import im.phantom.media.ffmpeg.FfmpegMediaProbe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAppServiceTest {

    @Mock private JdbcMediaRepository repository;
    private final InMemoryStorage storage = new InMemoryStorage();
    private MediaAppService service;

    @BeforeEach
    void setUp() {
        MediaProperties properties = new MediaProperties();
        properties.setPartSizeBytes(4);
        properties.setAccessTokenSecret("unit-test-secret");
        service = new MediaAppService(repository, storage, properties,
                new MediaAccessTokenService(properties), new ObjectMapper(), new ImageMediaProcessor() {
                    @Override public java.util.Optional<int[]> dimensions(byte[] content) {
                        return java.util.Optional.of(new int[]{2, 1});
                    }
                    @Override public java.util.Optional<byte[]> thumbnail(byte[] content, int maxSize) {
                        return java.util.Optional.of("jpeg".getBytes());
                    }
                });
    }

    @Test
    void resumesUploadAndReturnsUploadedParts() {
        when(repository.findResumableCandidates("app", "u1", "a".repeat(64)))
                .thenReturn(List.of(new UploadRecord("up1", "app", "u1", "a.bin",
                        "a".repeat(64), "application/octet-stream", 6, 4, "UPLOADING", null,
                        "{}", false, 0, 1, 2, 999_999)));
        when(repository.uploadedParts("up1")).thenReturn(List.of(1));

        var result = service.init("app", "u1", new InitUploadRequest("a.bin", 6,
                "a".repeat(64), "application/octet-stream", "FILE", null, null, null, false, null));

        assertThat(result.uploadId()).isEqualTo("up1");
        assertThat(result.uploadedParts()).containsExactly(1);
        verify(repository, never()).insertUpload(any());
    }

    @Test
    void instantUploadReturnsExistingMediaWithoutNewSession() {
        MediaRecord media = new MediaRecord("media1", "app", "u1", "key", "a".repeat(64),
                "image/png", 4, "IMAGE", null, "{\"fileName\":\"a.png\"}", false, "READY", 1, null);
        when(repository.findReusableCandidates("app", "u1", "a".repeat(64))).thenReturn(List.of(media));

        var result = service.init("app", "u1", new InitUploadRequest("a.png", 4,
                "a".repeat(64), "image/png", "IMAGE", null, 1, 1, false, null));

        assertThat(result.instant()).isTrue();
        assertThat(result.mediaId()).isEqualTo("media1");
        assertThat(result.url()).contains("/media/media1/content");
    }

    @Test
    void rejectsOversizedVideoBeforeCreatingSession() {
        InitUploadRequest request = new InitUploadRequest("v.mp4", 101L * 1024 * 1024,
                "a".repeat(64), "video/mp4", "VIDEO", 10, 1, 1, false, null);

        assertThatThrownBy(() -> service.init("app", "u1", request))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(ErrorCodes.MEDIA_TOO_LARGE);
    }

    @Test
    void completeRejectsShaMismatchAndDoesNotPublishObject() {
        String expected = "a".repeat(64);
        when(repository.findUpload("up1")).thenReturn(Optional.of(new UploadRecord(
                "up1", "app", "u1", "a.bin", expected, "application/octet-stream", 4, 4,
                "UPLOADING", null, "{}", false, 0, 1, 2, 999_999)));
        when(repository.listParts("up1")).thenReturn(List.of(new PartRecord(1, "x", 4)));
        storage.write("upload/up1/1", "data".getBytes());

        assertThatThrownBy(() -> service.complete("app", "u1", "up1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("摘要");
        verify(repository, never()).insertMedia(any());
    }

    @Test
    void completeImageUsesServerDimensionsAndServesDedicatedThumbnail() throws Exception {
        byte[] bytes = "fake-image".getBytes();
        String sha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(repository.findUpload("up1")).thenReturn(Optional.of(new UploadRecord(
                "up1", "app", "u1", "a.png", sha, "image/png", bytes.length, bytes.length,
                "UPLOADING", null, "{\"width\":99,\"height\":99,\"fileName\":\"a.png\"}",
                false, 0, 1, 2, 999_999)));
        when(repository.listParts("up1")).thenReturn(List.of(new PartRecord(1, "x", bytes.length)));
        storage.write("upload/up1/1", bytes);

        var completed = service.complete("app", "u1", "up1");

        ArgumentCaptor<MediaRecord> inserted = ArgumentCaptor.forClass(MediaRecord.class);
        verify(repository).insertMedia(inserted.capture());
        assertThat(inserted.getValue().metadataJson()).contains("\"width\":2", "\"height\":1");
        assertThat(completed.thumbUrl()).contains("variant=thumb");

        MediaRecord stored = inserted.getValue();
        storage.write(stored.thumbKey(), "jpeg".getBytes());
        when(repository.findMedia(stored.mediaId())).thenReturn(Optional.of(stored));
        long expiresAt = System.currentTimeMillis() + 60_000;
        String signature = new MediaAccessTokenService(properties()).sign(
                stored.mediaId(), "thumb", "u1", "app", expiresAt);

        assertThat(service.content("app", "u1", stored.mediaId(), expiresAt, signature, "thumb").bytes())
                .containsExactly('j', 'p', 'e', 'g');
    }

    @Test
    void rejectsCrossApplicationAndNonMemberMediaAccess() {
        MediaRecord otherApp = new MediaRecord("media1", "other-app", "u2", "key", "a".repeat(64),
                "image/png", 4, "IMAGE", null, "{}", false, "READY", 1, null);
        when(repository.findMedia("media1")).thenReturn(Optional.of(otherApp));

        assertThatThrownBy(() -> service.access("app", "u1", "media1"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(ErrorCodes.NO_PERMISSION);

        MediaRecord sameApp = new MediaRecord("media1", "app", "u2", "key", "a".repeat(64),
                "image/png", 4, "IMAGE", null, "{}", false, "READY", 1, null);
        when(repository.findMedia("media1")).thenReturn(Optional.of(sameApp));
        when(repository.canAccessMessageMedia("media1", "u1")).thenReturn(false);

        assertThatThrownBy(() -> service.access("app", "u1", "media1"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(ErrorCodes.NO_PERMISSION);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "配置.json|application/json|application/json",
            "data.custom|application/x-custom|application/x-custom",
            "archive.rar|application/vnd.rar|application/vnd.rar",
            "README|NULL|application/octet-stream",
            "unknown.xyz|''|application/octet-stream",
            "blank.bin|'   '|application/octet-stream",
            "index.html|text/html|text/html",
            "photo.png|image/png|image/png",
            "movie.mp4|video/mp4|video/mp4",
            "audio.mp3|audio/mpeg|audio/mpeg",
            "typed.json|Application/JSON|application/json",
            "charset.txt|text/plain;charset=UTF-8|text/plain;charset=UTF-8"
    }, delimiter = '|', nullValues = "NULL")
    void fileUploadRoundTripPreservesContentNameAndDeclaredType(String name, String mime, String expectedMime)
            throws Exception {
        FfmpegMediaProbe probe = mock(FfmpegMediaProbe.class);
        ImageMediaProcessor images = mock(ImageMediaProcessor.class);
        service = new MediaAppService(repository, storage, properties(),
                new MediaAccessTokenService(properties()), new ObjectMapper(), probe, images);
        byte[] bytes = "{\"说明\":\"普通附件\"}".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var initialized = service.init("app", "u1", new InitUploadRequest(name, bytes.length,
                sha, mime, "FILE", null, null, null, false, null));
        ArgumentCaptor<UploadRecord> uploads = ArgumentCaptor.forClass(UploadRecord.class);
        verify(repository).insertUpload(uploads.capture());
        UploadRecord upload = uploads.getValue();
        assertThat(upload.mimeType()).isEqualTo(expectedMime);
        assertThat(upload.metadataJson()).contains("\"mediaType\":\"FILE\"");
        when(repository.findUpload(initialized.uploadId())).thenReturn(Optional.of(upload));
        service.uploadPart("app", "u1", upload.uploadId(), 1, bytes);
        when(repository.listParts(upload.uploadId())).thenReturn(List.of(new PartRecord(1, sha, bytes.length)));

        var completed = service.complete("app", "u1", upload.uploadId());
        ArgumentCaptor<MediaRecord> media = ArgumentCaptor.forClass(MediaRecord.class);
        verify(repository).insertMedia(media.capture());
        MediaRecord stored = media.getValue();
        assertThat(stored.mediaType()).isEqualTo("FILE");
        assertThat(completed.thumbUrl()).isNull();
        verifyNoInteractions(probe, images);
        when(repository.findMedia(stored.mediaId())).thenReturn(Optional.of(stored));
        long expiresAt = System.currentTimeMillis() + 60_000;
        String signature = new MediaAccessTokenService(properties()).sign(
                stored.mediaId(), "original", "u1", "app", expiresAt);
        var downloaded = service.content("app", "u1", stored.mediaId(), expiresAt, signature, "original");
        assertThat(downloaded.bytes()).isEqualTo(bytes);
        assertThat(downloaded.fileName()).isEqualTo(name);
        assertThat(downloaded.mimeType()).isEqualTo(expectedMime);
        assertThat(downloaded.mediaType()).isEqualTo("FILE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-mime", "*/*", "image/*", "application/*+json", "text/plain\r\nX-Test: bad"})
    void rejectsMalformedMimeBeforeCreatingSession(String mime) {
        assertThatThrownBy(() -> service.init("app", "u1", request("data", mime, "FILE", 4)))
                .isInstanceOf(BizException.class).hasMessage("文件内容类型格式不正确");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"IMAGE,application/json,1", "VIDEO,application/json,1", "VOICE,application/json,1",
            "VOICE,audio/mpeg,0", "VOICE,audio/mpeg,61"})
    void retainsDedicatedMediaValidation(String type, String mime, int duration) {
        assertThatThrownBy(() -> service.init("app", "u1", new InitUploadRequest("data", 4,
                "a".repeat(64), mime, type, duration, null, null, false, null)))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "VOICE"})
    void doesNotDefaultMissingMimeForDedicatedMedia(String type) {
        assertThatThrownBy(() -> service.init("app", "u1", request("data", null, type, 4)))
                .isInstanceOf(BizException.class).hasMessage("请提供媒体内容类型");
        verifyNoInteractions(repository);
    }

    @Test
    void fileSizeLimitIsIndependentOfMime() {
        service.init("app", "u1", request("movie.mp4", "video/mp4", "FILE", 500L * 1024 * 1024));
        verify(repository).insertUpload(any());
        assertThatThrownBy(() -> service.init("app", "u1",
                request("movie.mp4", "video/mp4", "FILE", 500L * 1024 * 1024 + 1)))
                .isInstanceOf(BizException.class).hasMessage("文件超过 500MB");
    }

    @Test
    void instantUploadSkipsDifferentTypeNameMimeSizeAndBurnPolicy() {
        MediaRecord matching = reusable("matching", "FILE", "a.png", "image/png", 4, false);
        when(repository.findReusableCandidates("app", "u1", "a".repeat(64))).thenReturn(List.of(
                reusable("image", "IMAGE", "a.png", "image/png", 4, false),
                reusable("renamed", "FILE", "b.png", "image/png", 4, false),
                reusable("other-mime", "FILE", "a.png", "application/octet-stream", 4, false),
                reusable("other-size", "FILE", "a.png", "image/png", 5, false),
                reusable("burn", "FILE", "a.png", "image/png", 4, true), matching));
        var result = service.init("app", "u1", request("a.png", "image/png", "FILE", 4));
        assertThat(result.instant()).isTrue();
        assertThat(result.mediaId()).isEqualTo("matching");
        verify(repository, never()).insertUpload(any());
    }

    @Test
    void createsNewUploadWhenOnlyAnotherMediaTypeExists() {
        when(repository.findReusableCandidates("app", "u1", "a".repeat(64))).thenReturn(List.of(
                reusable("image", "IMAGE", "a.png", "image/png", 4, false)));
        when(repository.findResumableCandidates("app", "u1", "a".repeat(64))).thenReturn(List.of(
                resumable("image-upload", "IMAGE", "a.png", "image/png", 4, false, 0)));
        var result = service.init("app", "u1", request("a.png", "image/png", "FILE", 4));
        assertThat(result.instant()).isFalse();
        assertThat(result.uploadId()).isNotEqualTo("image-upload");
        verify(repository).insertUpload(any());
    }

    @Test
    void resumeSkipsDifferentTypeNameMimeSizeAndBurnPolicy() {
        when(repository.findResumableCandidates("app", "u1", "a".repeat(64))).thenReturn(List.of(
                resumable("image", "IMAGE", "a.png", "image/png", 4, false, 0),
                resumable("name", "FILE", "b.png", "image/png", 4, false, 0),
                resumable("mime", "FILE", "a.png", "application/octet-stream", 4, false, 0),
                resumable("size", "FILE", "a.png", "image/png", 5, false, 0),
                resumable("burn", "FILE", "a.png", "image/png", 4, true, 0),
                resumable("ttl", "FILE", "a.png", "image/png", 4, false, 60),
                resumable("matching", "FILE", "a.png", "image/png", 4, false, 0)));
        when(repository.uploadedParts("matching")).thenReturn(List.of(1));
        var result = service.init("app", "u1", request("a.png", "image/png", "FILE", 4));
        assertThat(result.uploadId()).isEqualTo("matching");
        assertThat(result.uploadedParts()).containsExactly(1);
        verify(repository, never()).insertUpload(any());
    }

    private InitUploadRequest request(String name, String mime, String type, long size) {
        return new InitUploadRequest(name, size, "a".repeat(64), mime, type, null, null, null, false, null);
    }

    private MediaRecord reusable(String id, String type, String name, String mime, long size, boolean burn) {
        return new MediaRecord(id, "app", "u1", "key", "a".repeat(64), mime, size, type, null,
                "{\"fileName\":\"" + name + "\"}", burn, "READY", 1, null);
    }

    private UploadRecord resumable(String id, String type, String name, String mime, long size,
                                  boolean burn, int ttl) {
        return new UploadRecord(id, "app", "u1", name, "a".repeat(64), mime, size, 4, "UPLOADING", null,
                "{\"mediaType\":\"" + type + "\"}", burn, ttl, 1, 2, Long.MAX_VALUE);
    }

    private MediaProperties properties() {
        MediaProperties properties = new MediaProperties();
        properties.setAccessTokenSecret("unit-test-secret");
        return properties;
    }

    private static final class InMemoryStorage implements MediaStorage {
        private final Map<String, byte[]> values = new ConcurrentHashMap<>();
        @Override public void write(String key, byte[] content) { values.put(key, content.clone()); }
        @Override public byte[] read(String key) { return values.getOrDefault(key, new byte[0]).clone(); }
        @Override public boolean exists(String key) { return values.containsKey(key); }
        @Override public void delete(String key) { values.remove(key); }
    }
}
