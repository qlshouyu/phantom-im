package im.phantom.media.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.media.MediaProperties;
import im.phantom.media.ffmpeg.FfmpegBinaryManager.Toolchain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 使用首次释放的 ffprobe/ffmpeg 探测媒体并生成视频封面。 */
@Component
public class FfmpegMediaProbe {

    private static final Logger log = LoggerFactory.getLogger(FfmpegMediaProbe.class);
    private final FfmpegBinaryManager binaries;
    private final MediaProperties properties;
    private final ObjectMapper objectMapper;

    public FfmpegMediaProbe(FfmpegBinaryManager binaries, MediaProperties properties,
                            ObjectMapper objectMapper) {
        this.binaries = binaries;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Optional<ProbeResult> probe(byte[] content, String extension) {
        Optional<Toolchain> tools = binaries.ensureAvailable();
        if (tools.isEmpty()) return Optional.empty();
        Path input = null;
        try {
            input = materialize(content, extension);
            ProcessResult result = run(tools.get().ffprobe(), "-v", "error",
                    "-show_entries", "format=duration:stream=width,height,codec_name",
                    "-of", "json", input.toString());
            if (result.exitCode() != 0 || result.stdout().isBlank()) return Optional.empty();
            JsonNode root = objectMapper.readTree(result.stdout());
            JsonNode streams = root.path("streams");
            Integer width = null;
            Integer height = null;
            String codec = null;
            for (JsonNode stream : streams) {
                if (stream.has("width") || stream.has("height")) {
                    width = stream.has("width") ? stream.path("width").asInt() : null;
                    height = stream.has("height") ? stream.path("height").asInt() : null;
                    codec = stream.has("codec_name") ? stream.path("codec_name").asText() : null;
                    break;
                }
            }
            double duration = root.path("format").path("duration").asDouble(0);
            return Optional.of(new ProbeResult(width, height,
                    duration <= 0 ? null : (int) Math.ceil(duration), codec));
        } catch (Exception error) {
            log.warn("ffprobe 媒体探测失败: {}", error.toString());
            return Optional.empty();
        } finally {
            delete(input);
        }
    }

    public Optional<byte[]> videoThumbnail(byte[] content, String extension) {
        Optional<Toolchain> tools = binaries.ensureAvailable();
        if (tools.isEmpty()) return Optional.empty();
        Path input = null;
        Path output = null;
        try {
            input = materialize(content, extension);
            output = Files.createTempFile("phantom-video-thumb-", ".jpg");
            ProcessResult result = run(tools.get().ffmpeg(), "-hide_banner", "-loglevel", "error",
                    "-y", "-ss", "0", "-i", input.toString(), "-frames:v", "1",
                    "-vf", "scale=320:-2:force_original_aspect_ratio=decrease",
                    "-q:v", "3", output.toString());
            if (result.exitCode() != 0 || !Files.isRegularFile(output)
                    || Files.size(output) == 0) return Optional.empty();
            return Optional.of(Files.readAllBytes(output));
        } catch (Exception error) {
            log.warn("ffmpeg 视频封面生成失败: {}", error.toString());
            return Optional.empty();
        } finally {
            delete(input);
            delete(output);
        }
    }

    private Path materialize(byte[] content, String extension) throws IOException {
        String suffix = extension == null || !extension.matches("\\.[a-z0-9]{1,10}") ? ".bin" : extension;
        Path input = Files.createTempFile("phantom-media-probe-", suffix);
        Files.write(input, content);
        return input;
    }

    private ProcessResult run(Path executable, String... args) throws Exception {
        Path stdout = Files.createTempFile("phantom-ffmpeg-out-", ".log");
        Path stderr = Files.createTempFile("phantom-ffmpeg-err-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command().add(executable.toString());
            builder.command().addAll(java.util.List.of(args));
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(properties.getFfmpegTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffmpeg 命令超时");
            }
            return new ProcessResult(process.exitValue(), Files.readString(stdout),
                    Files.readString(stderr));
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private void delete(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { /* best effort */ }
    }

    public record ProbeResult(Integer width, Integer height, Integer durationSeconds, String codec) {}
    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
