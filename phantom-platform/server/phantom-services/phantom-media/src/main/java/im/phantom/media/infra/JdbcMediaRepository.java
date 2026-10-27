package im.phantom.media.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 媒体元数据、上传会话和访问权限查询。 */
@Repository
public class JdbcMediaRepository {

    private final JdbcTemplate jdbc;

    public JdbcMediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MediaRecord> findReusable(String appId, String ownerId, String sha256) {
        return jdbc.query("""
                SELECT media_id, app_id, owner_id, storage_key, sha256, mime_type, size_bytes, media_type,
                       thumb_key, metadata_json, burn_after_read, status, created_at, expires_at
                FROM media_object WHERE app_id=? AND owner_id=? AND sha256=? AND status='READY'
                  AND (expires_at IS NULL OR expires_at>?) ORDER BY created_at LIMIT 1
                """, rs -> rs.next() ? Optional.of(mapMedia(rs)) : Optional.empty(),
                appId, ownerId, sha256, System.currentTimeMillis());
    }

    public Optional<UploadRecord> findResumable(String appId, String ownerId, String sha256) {
        return jdbc.query("""
                SELECT upload_id, app_id, owner_id, file_name, sha256, mime_type, size_bytes, part_size,
                       status, media_id, metadata_json, burn_after_read, burn_ttl_seconds,
                       created_at, updated_at, expires_at
                FROM media_upload WHERE app_id=? AND owner_id=? AND sha256=?
                  AND status IN ('INIT','UPLOADING') AND expires_at>?
                ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? Optional.of(mapUpload(rs)) : Optional.empty(), appId, ownerId, sha256,
                System.currentTimeMillis());
    }

    public List<Integer> uploadedParts(String uploadId) {
        return jdbc.queryForList("SELECT part_number FROM media_upload_part WHERE upload_id=? " +
                "ORDER BY part_number", Integer.class, uploadId);
    }

    public Optional<UploadRecord> findUpload(String uploadId) {
        return jdbc.query("""
                SELECT upload_id, app_id, owner_id, file_name, sha256, mime_type, size_bytes, part_size,
                       status, media_id, metadata_json, burn_after_read, burn_ttl_seconds,
                       created_at, updated_at, expires_at
                FROM media_upload WHERE upload_id=?
                """, rs -> rs.next() ? Optional.of(mapUpload(rs)) : Optional.empty(), uploadId);
    }

    public void insertUpload(UploadRecord upload) {
        jdbc.update("""
                INSERT INTO media_upload(upload_id, app_id, owner_id, file_name, sha256, mime_type,
                    size_bytes, part_size, status, media_id, metadata_json, burn_after_read,
                    burn_ttl_seconds, created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, upload.uploadId(), upload.appId(), upload.ownerId(), upload.fileName(), upload.sha256(),
                upload.mimeType(), upload.sizeBytes(), upload.partSize(), upload.status(), upload.mediaId(),
                upload.metadataJson(), upload.burnAfterRead(), upload.burnTtlSeconds(),
                upload.createdAt(), upload.updatedAt(), upload.expiresAt());
    }

    public void touchUpload(String uploadId, String status, long now) {
        jdbc.update("UPDATE media_upload SET status=?, updated_at=? WHERE upload_id=?",
                status, now, uploadId);
    }

    public void upsertPart(String uploadId, int partNumber, String etag, long sizeBytes, long now) {
        jdbc.update("""
                INSERT INTO media_upload_part(upload_id, part_number, etag, size_bytes, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE etag=VALUES(etag), size_bytes=VALUES(size_bytes)
                """, uploadId, partNumber, etag, sizeBytes, now);
    }

    public List<PartRecord> listParts(String uploadId) {
        return jdbc.query("SELECT part_number, etag, size_bytes FROM media_upload_part " +
                        "WHERE upload_id=? ORDER BY part_number", (rs, row) -> new PartRecord(
                rs.getInt(1), rs.getString(2), rs.getLong(3)), uploadId);
    }

    public void completeUpload(String uploadId, String mediaId, long now) {
        jdbc.update("UPDATE media_upload SET status='COMPLETED', media_id=?, updated_at=? WHERE upload_id=?",
                mediaId, now, uploadId);
    }

    public void insertMedia(MediaRecord media) {
        jdbc.update("""
                INSERT INTO media_object(media_id, app_id, owner_id, storage_key, sha256, mime_type,
                    size_bytes, media_type, thumb_key, metadata_json, burn_after_read, status,
                    created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, media.mediaId(), media.appId(), media.ownerId(), media.storageKey(), media.sha256(),
                media.mimeType(), media.sizeBytes(), media.mediaType(), media.thumbKey(),
                media.metadataJson(), media.burnAfterRead(), media.status(), media.createdAt(),
                media.expiresAt());
    }

    public Optional<MediaRecord> findMedia(String mediaId) {
        return jdbc.query("""
                SELECT media_id, app_id, owner_id, storage_key, sha256, mime_type, size_bytes, media_type,
                       thumb_key, metadata_json, burn_after_read, status, created_at, expires_at
                FROM media_object WHERE media_id=?
                """, rs -> rs.next() ? Optional.of(mapMedia(rs)) : Optional.empty(), mediaId);
    }

    public boolean canAccessMessageMedia(String mediaId, String userId) {
        Integer count = jdbc.query("""
                SELECT COUNT(*) FROM message_media_ref r
                JOIN conversation_member cm ON cm.conv_id=r.conv_id AND cm.user_id=? AND cm.left_at IS NULL
                WHERE r.media_id=?
                """, rs -> rs.next() ? rs.getInt(1) : 0, userId, mediaId);
        return count != null && count > 0;
    }

    public List<UploadRecord> expiredUploads(long now, int limit) {
        return jdbc.query("""
                SELECT upload_id, app_id, owner_id, file_name, sha256, mime_type, size_bytes, part_size,
                       status, media_id, metadata_json, burn_after_read, burn_ttl_seconds,
                       created_at, updated_at, expires_at
                FROM media_upload WHERE status IN ('INIT','UPLOADING') AND expires_at<=?
                ORDER BY expires_at LIMIT ?
                """, (rs, row) -> mapUpload(rs), now, limit);
    }

    public List<PartRecord> listPartsForCleanup(String uploadId) {
        return listParts(uploadId);
    }

    public void expireUpload(String uploadId, long now) {
        jdbc.update("UPDATE media_upload SET status='EXPIRED', updated_at=? WHERE upload_id=?",
                now, uploadId);
        jdbc.update("DELETE FROM media_upload_part WHERE upload_id=?", uploadId);
    }

    public List<MediaRecord> cleanupCandidates(long now, int limit) {
        return jdbc.query("""
                SELECT m.media_id, m.app_id, m.owner_id, m.storage_key, m.sha256, m.mime_type,
                       m.size_bytes, m.media_type, m.thumb_key, m.metadata_json, m.burn_after_read, m.status,
                       m.created_at, m.expires_at
                FROM media_object m
                WHERE m.status='READY' AND m.expires_at IS NOT NULL AND m.expires_at<=?
                  AND (m.burn_after_read=TRUE OR NOT EXISTS
                       (SELECT 1 FROM message_media_ref r WHERE r.media_id=m.media_id))
                ORDER BY m.expires_at LIMIT ?
                """, (rs, row) -> mapMedia(rs), now, limit);
    }

    public void markDeleted(String mediaId) {
        jdbc.update("UPDATE media_object SET status='DELETED' WHERE media_id=?", mediaId);
    }

    private MediaRecord mapMedia(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MediaRecord(rs.getString("media_id"), rs.getString("app_id"), rs.getString("owner_id"),
                rs.getString("storage_key"), rs.getString("sha256"), rs.getString("mime_type"),
                rs.getLong("size_bytes"), rs.getString("media_type"), rs.getString("thumb_key"),
                rs.getString("metadata_json"), rs.getBoolean("burn_after_read"), rs.getString("status"),
                rs.getLong("created_at"),
                (Long) rs.getObject("expires_at"));
    }

    private UploadRecord mapUpload(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UploadRecord(rs.getString("upload_id"), rs.getString("app_id"), rs.getString("owner_id"),
                rs.getString("file_name"), rs.getString("sha256"), rs.getString("mime_type"),
                rs.getLong("size_bytes"), rs.getInt("part_size"), rs.getString("status"),
                rs.getString("media_id"), rs.getString("metadata_json"), rs.getBoolean("burn_after_read"),
                rs.getInt("burn_ttl_seconds"), rs.getLong("created_at"), rs.getLong("updated_at"),
                rs.getLong("expires_at"));
    }

    public record UploadRecord(String uploadId, String appId, String ownerId, String fileName, String sha256,
                               String mimeType, long sizeBytes, int partSize, String status, String mediaId,
                               String metadataJson, boolean burnAfterRead, int burnTtlSeconds,
                               long createdAt, long updatedAt, long expiresAt) {}

    public record PartRecord(int partNumber, String etag, long sizeBytes) {}

    public record MediaRecord(String mediaId, String appId, String ownerId, String storageKey, String sha256,
                              String mimeType, long sizeBytes, String mediaType, String thumbKey,
                              String metadataJson, boolean burnAfterRead, String status,
                              long createdAt, Long expiresAt) {}
}
