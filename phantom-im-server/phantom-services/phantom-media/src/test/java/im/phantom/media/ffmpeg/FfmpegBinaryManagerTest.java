package im.phantom.media.ffmpeg;

import im.phantom.media.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegBinaryManagerTest {

    @TempDir Path tempDir;

    @Test
    void extractsCurrentPlatformOnceAndReusesCachedTools() throws Exception {
        Path resources = tempDir.resolve("resources/ffmpeg/linux/x86_64");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("ffmpeg"), "#!/bin/sh\necho ffmpeg\n");
        Files.writeString(resources.resolve("ffprobe"), "#!/bin/sh\necho ffprobe\n");
        MediaProperties properties = properties();
        FfmpegBinaryManager manager = new FfmpegBinaryManager(properties,
                name -> new FileSystemResource(tempDir.resolve("resources").resolve(name)));

        var first = manager.ensureAvailable().orElseThrow();
        assertThat(first.ffmpeg()).isExecutable();
        assertThat(first.ffprobe()).isExecutable();
        assertThat(first.directory().resolve(".ready")).exists();

        Files.delete(resources.resolve("ffmpeg"));
        Files.delete(resources.resolve("ffprobe"));
        var second = manager.ensureAvailable().orElseThrow();
        assertThat(second.directory()).isEqualTo(first.directory());
    }

    @Test
    void extractsRecognizedFilesFromZipAndRejectsUnknownEntries() throws Exception {
        Path resources = tempDir.resolve("resources/ffmpeg/macos/aarch64");
        Files.createDirectories(resources);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                resources.resolve("ffmpeg-tools.zip")))) {
            add(zip, "bin/ffmpeg", "#!/bin/sh\necho ffmpeg\n");
            add(zip, "bin/ffprobe", "#!/bin/sh\necho ffprobe\n");
            add(zip, "bin/malware", "ignored");
        }
        MediaProperties properties = properties();
        properties.setFfmpegPlatform("macos");
        properties.setFfmpegArch("aarch64");
        FfmpegBinaryManager manager = new FfmpegBinaryManager(properties,
                name -> new FileSystemResource(tempDir.resolve("resources").resolve(name)));

        var tools = manager.ensureAvailable().orElseThrow();
        assertThat(tools.ffmpeg()).isExecutable();
        assertThat(tools.directory().resolve("malware")).doesNotExist();
    }

    @Test
    void missingResourceReturnsEmptyWithoutFailingStartup() {
        MediaProperties properties = properties();
        FfmpegBinaryManager manager = new FfmpegBinaryManager(properties,
                name -> new FileSystemResource(tempDir.resolve("missing").resolve(name)));

        assertThat(manager.ensureAvailable()).isEmpty();
    }

    private MediaProperties properties() {
        MediaProperties properties = new MediaProperties();
        properties.setFfmpegPlatform("linux");
        properties.setFfmpegArch("x86_64");
        properties.setToolRoot(tempDir.resolve("tools"));
        properties.setFfmpegVersion("test-v1");
        return properties;
    }

    private void add(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
