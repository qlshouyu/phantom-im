package im.phantom.media.ffmpeg;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.media.MediaProperties;
import im.phantom.media.ffmpeg.FfmpegBinaryManager.Toolchain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FfmpegMediaProbeTest {

    @Mock FfmpegBinaryManager binaries;
    @TempDir Path tempDir;

    @Test
    void probesDimensionsAndDurationAndGeneratesCover() throws Exception {
        Path ffprobe = executable("ffprobe", "#!/bin/sh\nprintf '%s' '{\"streams\":[{\"width\":640,\"height\":360,\"codec_name\":\"h264\"}],\"format\":{\"duration\":\"12.4\"}}'\n");
        Path ffmpeg = executable("ffmpeg", "#!/bin/sh\nfor last do :; done\nprintf 'jpeg' > \"$last\"\n");
        when(binaries.ensureAvailable()).thenReturn(Optional.of(new Toolchain(
                "linux", "x86_64", tempDir, ffmpeg, ffprobe)));
        MediaProperties properties = new MediaProperties();
        properties.setFfmpegTimeoutSeconds(5);
        FfmpegMediaProbe probe = new FfmpegMediaProbe(binaries, properties, new ObjectMapper());

        var metadata = probe.probe("video".getBytes(), ".mp4").orElseThrow();
        assertThat(metadata.width()).isEqualTo(640);
        assertThat(metadata.height()).isEqualTo(360);
        assertThat(metadata.durationSeconds()).isEqualTo(13);
        assertThat(metadata.codec()).isEqualTo("h264");
        assertThat(probe.videoThumbnail("video".getBytes(), ".mp4").orElseThrow())
                .containsExactly('j', 'p', 'e', 'g');
    }

    private Path executable(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }
}
