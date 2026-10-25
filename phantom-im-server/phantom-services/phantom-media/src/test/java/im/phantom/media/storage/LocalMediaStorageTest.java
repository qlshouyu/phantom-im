package im.phantom.media.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMediaStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void writesReadsAndDeletesObject() {
        LocalMediaStorage storage = new LocalMediaStorage(tempDir);

        storage.write("app/2026/a.bin", new byte[]{1, 2, 3});

        assertThat(storage.exists("app/2026/a.bin")).isTrue();
        assertThat(storage.read("app/2026/a.bin")).containsExactly(1, 2, 3);
        storage.delete("app/2026/a.bin");
        assertThat(storage.exists("app/2026/a.bin")).isFalse();
    }

    @Test
    void rejectsPathTraversal() {
        LocalMediaStorage storage = new LocalMediaStorage(tempDir);

        assertThatThrownBy(() -> storage.write("../escape.bin", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
