package im.phantom.media.ffmpeg;

import im.phantom.media.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 首次启动从 classpath 释放当前平台/架构的 ffmpeg、ffprobe。
 * 释放目录按版本隔离；版本标记和可执行位校验通过后不再重复释放。
 */
@Component
public class FfmpegBinaryManager implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(FfmpegBinaryManager.class);
    private static final String MARKER = ".ready";
    private final MediaProperties properties;
    private final Function<String, Resource> resources;

    @Autowired
    public FfmpegBinaryManager(MediaProperties properties) {
        this(properties, ClassPathResource::new);
    }

    FfmpegBinaryManager(MediaProperties properties, Function<String, Resource> resources) {
        this.properties = properties;
        this.resources = resources;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (properties.isFfmpegEnabled()) {
            ensureAvailable().ifPresentOrElse(
                    tools -> log.info("ffmpeg 工具可用 platform={} arch={} path={}",
                            tools.platform(), tools.arch(), tools.directory()),
                    () -> log.warn("classpath 未提供当前平台 ffmpeg 工具，视频封面和媒体探测将降级"));
        }
    }

    /** 幂等释放；未打包当前平台工具时返回 empty，不阻塞应用启动。 */
    public synchronized Optional<Toolchain> ensureAvailable() {
        if (!properties.isFfmpegEnabled()) {
            return Optional.empty();
        }
        String platform = platform();
        String arch = arch();
        String root = properties.getFfmpegResourceRoot().replaceAll("/+$", "");
        String base = root + "/" + platform + "/" + arch;
        String suffix = "windows".equals(platform) ? ".exe" : "";
        String ffmpegName = "ffmpeg" + suffix;
        String ffprobeName = "ffprobe" + suffix;
        Path directory = properties.getToolRoot().toAbsolutePath().normalize()
                .resolve(properties.getFfmpegVersion()).resolve(platform + "-" + arch);
        Path ffmpegPath = directory.resolve(ffmpegName);
        Path ffprobePath = directory.resolve(ffprobeName);
        String markerPrefix = properties.getFfmpegVersion() + "\n";
        if (valid(directory, markerPrefix, ffmpegPath, ffprobePath)) {
            return Optional.of(new Toolchain(platform, arch, directory, ffmpegPath, ffprobePath));
        }
        try {
            Files.createDirectories(directory.getParent());
            Path lockPath = directory.getParent().resolve(".ffmpeg-" + platform + "-" + arch + ".lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                // 多实例同时首次启动时只允许一个进程释放，其余进程在锁内重新检查缓存。
                if (valid(directory, markerPrefix, ffmpegPath, ffprobePath)) {
                    return Optional.of(new Toolchain(platform, arch, directory, ffmpegPath, ffprobePath));
                }
                Resource archive = resources.apply(base + "/ffmpeg-tools.zip");
                Resource ffmpeg = resources.apply(base + "/" + ffmpegName);
                Resource ffprobe = resources.apply(base + "/" + ffprobeName);
                if (!archive.exists() && (!ffmpeg.exists() || !ffprobe.exists())) {
                    return Optional.empty();
                }
                String sourceHash = archive.exists() ? digest(archive) : digest(ffmpeg) + digest(ffprobe);
                String marker = markerPrefix + sourceHash + "\n";
                extractAtomically(directory, archive, ffmpeg, ffprobe, ffmpegName, ffprobeName, marker);
                return Optional.of(new Toolchain(platform, arch, directory, ffmpegPath, ffprobePath));
            }
        } catch (Exception error) {
            log.error("释放 ffmpeg 工具失败 platform={} arch={}", platform, arch, error);
            throw new IllegalStateException("释放 ffmpeg 工具失败: " + platform + "/" + arch, error);
        }
    }

    private void extractAtomically(Path directory, Resource archive, Resource ffmpeg, Resource ffprobe,
                                   String ffmpegName, String ffprobeName, String marker) throws IOException {
        Files.createDirectories(directory.getParent());
        Path temp = directory.getParent().resolve(directory.getFileName() + ".tmp-" + UUID.randomUUID());
        Path backup = directory.getParent().resolve(directory.getFileName() + ".old-" + UUID.randomUUID());
        Files.createDirectories(temp);
        try {
            if (archive.exists()) {
                extractZip(archive, temp, Set.of(ffmpegName, ffprobeName));
            } else {
                copy(ffmpeg, temp.resolve(ffmpegName));
                copy(ffprobe, temp.resolve(ffprobeName));
            }
            requireExecutable(temp.resolve(ffmpegName));
            requireExecutable(temp.resolve(ffprobeName));
            Files.writeString(temp.resolve(MARKER), marker);
            boolean hadPrevious = Files.exists(directory);
            if (hadPrevious) moveDirectory(directory, backup);
            try {
                moveDirectory(temp, directory);
                if (hadPrevious) deleteRecursively(backup);
            } catch (IOException moveFailure) {
                if (hadPrevious && !Files.exists(directory) && Files.exists(backup)) {
                    moveDirectory(backup, directory);
                }
                throw moveFailure;
            }
        } finally {
            if (Files.exists(temp)) {
                deleteRecursively(temp);
            }
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(source, target);
        }
    }

    private void extractZip(Resource resource, Path target, Set<String> expected) throws IOException {
        try (InputStream input = resource.getInputStream(); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String fileName = Path.of(entry.getName()).getFileName().toString();
                if (!expected.contains(fileName)) continue;
                Path output = target.resolve(fileName).normalize();
                if (!output.startsWith(target)) throw new IOException("非法 ffmpeg 包路径: " + entry.getName());
                Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void copy(Resource resource, Path target) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean valid(Path directory, String marker, Path ffmpeg, Path ffprobe) {
        try {
            return Files.isRegularFile(directory.resolve(MARKER))
                    && Files.readString(directory.resolve(MARKER)).startsWith(marker)
                    && Files.isRegularFile(ffmpeg) && Files.isExecutable(ffmpeg)
                    && Files.isRegularFile(ffprobe) && Files.isExecutable(ffprobe);
        } catch (IOException invalid) {
            return false;
        }
    }

    private void requireExecutable(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("工具包缺少文件: " + path.getFileName());
        if (!isWindows()) {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        }
        if (!Files.isExecutable(path) && !isWindows()) throw new IOException("工具不可执行: " + path);
    }

    private String digest(Resource resource) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = resource.getInputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    String platform() {
        if (properties.getFfmpegPlatform() != null && !properties.getFfmpegPlatform().isBlank()) {
            return properties.getFfmpegPlatform().toLowerCase(Locale.ROOT);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        return os.replaceAll("[^a-z0-9]+", "-");
    }

    String arch() {
        if (properties.getFfmpegArch() != null && !properties.getFfmpegArch().isBlank()) {
            return properties.getFfmpegArch().toLowerCase(Locale.ROOT);
        }
        String value = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (value.contains("aarch64") || value.contains("arm64")) return "aarch64";
        if (value.contains("amd64") || value.contains("x86_64")) return "x86_64";
        if (value.startsWith("arm")) return "armv7";
        return value.replaceAll("[^a-z0-9]+", "-");
    }

    private boolean isWindows() { return "windows".equals(platform()); }

    private void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record Toolchain(String platform, String arch, Path directory,
                            Path ffmpeg, Path ffprobe) {}
}
