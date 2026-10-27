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
        when(repository.findResumable("app", "u1", "a".repeat(64)))
                .thenReturn(Optional.of(new UploadRecord("up1", "app", "u1", "a.bin",
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
                "image/png", 4, "IMAGE", null, "{}", false, "READY", 1, null);
        when(repository.findReusable("app", "u1", "a".repeat(64))).thenReturn(Optional.of(media));

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
