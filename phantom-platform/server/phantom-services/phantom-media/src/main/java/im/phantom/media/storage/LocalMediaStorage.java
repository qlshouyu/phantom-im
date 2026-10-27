package im.phantom.media.storage;

import im.phantom.media.spi.MediaStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 本地文件系统媒体存储，用于开发和 E2E。 */
public class LocalMediaStorage implements MediaStorage {

    private final Path root;

    public LocalMediaStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void write(String storageKey, byte[] content) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("媒体写入失败: " + storageKey, e);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException e) {
            throw new IllegalStateException("媒体读取失败: " + storageKey, e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.isRegularFile(resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new IllegalStateException("媒体删除失败: " + storageKey, e);
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        Path relative = Path.of(storageKey).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("非法媒体存储键: " + storageKey);
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("媒体路径越界: " + storageKey);
        }
        return target;
    }
}
