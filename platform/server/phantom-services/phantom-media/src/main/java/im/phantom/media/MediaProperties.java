package im.phantom.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** 媒体服务配置。 */
@ConfigurationProperties(prefix = "phantom.media")
public class MediaProperties {

    /** 本地开发存储根目录。 */
    private Path localRoot = Path.of(System.getProperty("java.io.tmpdir"), "phantom-media");
    private int partSizeBytes = 5 * 1024 * 1024;
    private long uploadTtlSeconds = 24 * 60 * 60;
    private long unboundMediaTtlSeconds = 24 * 60 * 60;
    private String accessTokenSecret = "phantom-media-local-secret-change-me";
    private long accessUrlTtlSeconds = 15 * 60;
    /** ffmpeg/ffprobe 首次释放目录；容器部署时应挂载为持久卷。 */
    private Path toolRoot = Path.of(System.getProperty("java.io.tmpdir"), "phantom-media-tools");
    /** classpath 下按 {platform}/{arch}/ 分目录存放工具包。 */
    private String ffmpegResourceRoot = "ffmpeg";
    /** 更换二进制包时必须同步更新版本，旧缓存会重新释放。 */
    private String ffmpegVersion = "bundled-v1";
    private boolean ffmpegEnabled = true;
    private long ffmpegTimeoutSeconds = 20;
    /** 仅用于测试/特殊部署；为空时自动识别操作系统与 CPU 架构。 */
    private String ffmpegPlatform;
    private String ffmpegArch;

    public Path getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(Path localRoot) {
        this.localRoot = localRoot;
    }

    public int getPartSizeBytes() { return partSizeBytes; }
    public void setPartSizeBytes(int partSizeBytes) { this.partSizeBytes = partSizeBytes; }
    public long getUploadTtlSeconds() { return uploadTtlSeconds; }
    public void setUploadTtlSeconds(long uploadTtlSeconds) { this.uploadTtlSeconds = uploadTtlSeconds; }
    public long getUnboundMediaTtlSeconds() { return unboundMediaTtlSeconds; }
    public void setUnboundMediaTtlSeconds(long value) { this.unboundMediaTtlSeconds = value; }
    public String getAccessTokenSecret() { return accessTokenSecret; }
    public void setAccessTokenSecret(String accessTokenSecret) { this.accessTokenSecret = accessTokenSecret; }
    public long getAccessUrlTtlSeconds() { return accessUrlTtlSeconds; }
    public void setAccessUrlTtlSeconds(long value) { this.accessUrlTtlSeconds = value; }
    public Path getToolRoot() { return toolRoot; }
    public void setToolRoot(Path toolRoot) { this.toolRoot = toolRoot; }
    public String getFfmpegResourceRoot() { return ffmpegResourceRoot; }
    public void setFfmpegResourceRoot(String value) { this.ffmpegResourceRoot = value; }
    public String getFfmpegVersion() { return ffmpegVersion; }
    public void setFfmpegVersion(String value) { this.ffmpegVersion = value; }
    public boolean isFfmpegEnabled() { return ffmpegEnabled; }
    public void setFfmpegEnabled(boolean value) { this.ffmpegEnabled = value; }
    public long getFfmpegTimeoutSeconds() { return ffmpegTimeoutSeconds; }
    public void setFfmpegTimeoutSeconds(long value) { this.ffmpegTimeoutSeconds = value; }
    public String getFfmpegPlatform() { return ffmpegPlatform; }
    public void setFfmpegPlatform(String value) { this.ffmpegPlatform = value; }
    public String getFfmpegArch() { return ffmpegArch; }
    public void setFfmpegArch(String value) { this.ffmpegArch = value; }
}
