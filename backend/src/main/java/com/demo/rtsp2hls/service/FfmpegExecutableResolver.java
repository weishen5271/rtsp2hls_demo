package com.demo.rtsp2hls.service;

import com.demo.rtsp2hls.config.StreamProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class FfmpegExecutableResolver {

    private static final Logger log = LoggerFactory.getLogger(FfmpegExecutableResolver.class);

    private final StreamProperties streamProperties;
    private volatile Path extractedExecutable;

    public FfmpegExecutableResolver(StreamProperties streamProperties) {
        this.streamProperties = streamProperties;
    }

    public String resolveExecutable() {
        String configured = streamProperties.getFfmpegExecutable();
        if (configured != null && !configured.isBlank() && !"ffmpeg".equalsIgnoreCase(configured.trim())) {
            return configured.trim();
        }

        Path bundledPath = extractBundledExecutableIfPresent();
        if (bundledPath != null) {
            return bundledPath.toString();
        }

        return configured == null || configured.isBlank() ? "ffmpeg" : configured.trim();
    }

    private Path extractBundledExecutableIfPresent() {
        if (extractedExecutable != null && Files.exists(extractedExecutable)) {
            return extractedExecutable;
        }

        String platformDir = detectPlatformDir();
        String executableName = isWindows() ? "ffmpeg.exe" : "ffmpeg";
        String resourcePath = streamProperties.getBundledFfmpegRoot() + "/" + platformDir + "/" + executableName;
        ClassPathResource resource = new ClassPathResource(resourcePath);

        if (!resource.exists()) {
            log.debug("Bundled ffmpeg not found at classpath: {}", resourcePath);
            return null;
        }

        Path outputDir = Paths.get(streamProperties.getBundledFfmpegOutputDir()).toAbsolutePath().normalize();
        Path target = outputDir.resolve(platformDir).resolve(executableName);

        try {
            Files.createDirectories(target.getParent());
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!isWindows()) {
                target.toFile().setExecutable(true, true);
            }
            extractedExecutable = target;
            log.info("Using bundled ffmpeg executable: {}", target);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("解压内置 ffmpeg 失败：" + resourcePath, ex);
        }
    }

    private String detectPlatformDir() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String normalizedArch = normalizeArch(arch);

        if (osName.contains("win")) {
            return "win-" + normalizedArch;
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "mac-" + normalizedArch;
        }
        if (osName.contains("nux") || osName.contains("linux")) {
            return "linux-" + normalizedArch;
        }
        throw new IllegalStateException("暂不支持当前操作系统的内置 ffmpeg 分发：" + osName + "/" + arch);
    }

    private String normalizeArch(String arch) {
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("64")) {
            return "x64";
        }
        if (arch.contains("86")) {
            return "x86";
        }
        return arch.replaceAll("[^a-z0-9]+", "");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @PreDestroy
    public void cleanup() {
        if (extractedExecutable == null) {
            return;
        }
        try {
            Files.deleteIfExists(extractedExecutable);
            Path parent = extractedExecutable.getParent();
            if (parent != null && Files.isDirectory(parent) && isEmptyDir(parent)) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException ex) {
            log.debug("Skip cleanup bundled ffmpeg executable: {}", extractedExecutable, ex);
        }
    }

    private boolean isEmptyDir(Path path) throws IOException {
        try (var stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        }
    }
}
