package com.demo.rtsp2hls.service;

import com.demo.rtsp2hls.config.StreamProperties;
import com.demo.rtsp2hls.model.RtspProbeResponse;
import com.demo.rtsp2hls.model.StreamProcessHolder;
import com.demo.rtsp2hls.model.StreamResponse;
import java.io.BufferedReader;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);
    private static final Duration RTSP_PROBE_TIMEOUT = Duration.ofSeconds(8);

    private final StreamProperties streamProperties;
    private final FfmpegExecutableResolver ffmpegExecutableResolver;
    private final Map<String, StreamProcessHolder> holders = new ConcurrentHashMap<>();
    private final Map<String, String> sourceIndex = new ConcurrentHashMap<>();

    public StreamService(StreamProperties streamProperties, FfmpegExecutableResolver ffmpegExecutableResolver) {
        this.streamProperties = streamProperties;
        this.ffmpegExecutableResolver = ffmpegExecutableResolver;
    }

    public synchronized StreamResponse open(String rtspUrl) {
        String normalizedUrl = normalizeRtspUrl(rtspUrl);
        String sourceKey = normalizedUrl;

        String existingId = sourceIndex.get(sourceKey);
        if (existingId != null) {
            StreamProcessHolder existingHolder = holders.get(existingId);
            if (existingHolder != null && existingHolder.getProcess().isAlive()) {
                existingHolder.incrementViewer();
                return toResponse(existingHolder, "RUNNING", "复用已有转流");
            }
            sourceIndex.remove(sourceKey);
            if (existingHolder != null) {
                stopInternal(existingHolder);
            }
        }

        String streamId = UUID.randomUUID().toString().replace("-", "");
        Path rootDir = resolveOutputRoot();
        Path streamDir = rootDir.resolve(streamId);
        Path playlist = streamDir.resolve("index.m3u8");
        String playUrl = "/hls/" + streamId + "/index.m3u8";
        Instant now = Instant.now();

        try {
            Files.createDirectories(streamDir);
            Process process = startFfmpeg(normalizedUrl, playlist);
            StreamProcessHolder holder = new StreamProcessHolder(
                streamId,
                sourceKey,
                normalizedUrl,
                playUrl,
                streamDir,
                process,
                now,
                now,
                1
            );
            holders.put(streamId, holder);
            sourceIndex.put(sourceKey, streamId);
            waitUntilReady(holder, playlist);
            return toResponse(holder, "RUNNING", "转流已启动");
        } catch (IOException ex) {
            deleteQuietly(streamDir);
            throw new IllegalStateException("启动 ffmpeg 失败，请确认已安装并配置 ffmpeg。原因：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            forceClose(streamId);
            throw new IllegalStateException("等待 HLS 输出时被中断", ex);
        } catch (RuntimeException ex) {
            forceClose(streamId);
            throw ex;
        }
    }

    public RtspProbeResponse probe(String rtspUrl) {
        String normalizedUrl = normalizeRtspUrl(rtspUrl);
        Instant checkedAt = Instant.now();
        long startedAt = System.nanoTime();
        List<String> command = buildProbeCommand(normalizedUrl);

        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

            boolean finished = process.waitFor(RTSP_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            if (!finished) {
                process.destroyForcibly();
                return new RtspProbeResponse(
                    normalizedUrl,
                    false,
                    "OFFLINE",
                    checkedAt,
                    elapsedMillis,
                    "RTSP 探测超时，未在 " + RTSP_PROBE_TIMEOUT.toSeconds() + " 秒内拿到视频帧"
                );
            }

            String output = readProcessOutput(process);
            if (process.exitValue() == 0) {
                return new RtspProbeResponse(
                    normalizedUrl,
                    true,
                    "ONLINE",
                    checkedAt,
                    elapsedMillis,
                    "RTSP 流在线，可正常读取视频帧"
                );
            }

            return new RtspProbeResponse(
                normalizedUrl,
                false,
                "OFFLINE",
                checkedAt,
                elapsedMillis,
                buildProbeFailureMessage(output)
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RTSP 在线探测被中断", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("启动 RTSP 在线探测失败：" + ex.getMessage(), ex);
        }
    }

    public synchronized StreamResponse heartbeat(String streamId) {
        StreamProcessHolder holder = getExistingHolder(streamId);
        holder.heartbeat();
        return toResponse(holder, runtimeStatus(holder), "心跳已续期");
    }

    public synchronized StreamResponse release(String streamId) {
        StreamProcessHolder holder = holders.get(streamId);
        if (holder == null) {
            return notFound(streamId, "流不存在或已释放");
        }

        holder.decrementViewer();
        String message = holder.getViewerCount() == 0
            ? "观看者已释放，等待空闲回收"
            : "观看者已释放";
        return toResponse(holder, runtimeStatus(holder), message);
    }

    public synchronized StreamResponse close(String streamId) {
        return forceClose(streamId);
    }

    public synchronized StreamResponse detail(String streamId) {
        StreamProcessHolder holder = holders.get(streamId);
        if (holder == null) {
            return notFound(streamId, "流不存在");
        }
        return toResponse(holder, runtimeStatus(holder), statusMessage(holder));
    }

    public synchronized List<StreamResponse> list() {
        List<StreamResponse> responses = new ArrayList<>();
        for (StreamProcessHolder holder : holders.values()) {
            responses.add(toResponse(holder, runtimeStatus(holder), statusMessage(holder)));
        }
        return responses;
    }

    @Scheduled(fixedDelayString = "#{${app.stream.cleanup-interval-seconds:15} * 1000}")
    public synchronized void cleanupIdleStreams() {
        Instant now = Instant.now();
        List<String> closableIds = new ArrayList<>();

        for (StreamProcessHolder holder : holders.values()) {
            if (!holder.getProcess().isAlive()) {
                closableIds.add(holder.getId());
                continue;
            }

            if (holder.getViewerCount() > 0) {
                continue;
            }

            long idleSeconds = Duration.between(holder.getLastAccessAt(), now).getSeconds();
            if (idleSeconds >= streamProperties.getIdleTimeoutSeconds()) {
                closableIds.add(holder.getId());
            }
        }

        for (String streamId : closableIds) {
            StreamResponse response = forceClose(streamId);
            log.info("Cleanup stream {} -> {}", streamId, response.message());
        }
    }

    private StreamResponse forceClose(String streamId) {
        StreamProcessHolder holder = holders.remove(streamId);
        if (holder == null) {
            return notFound(streamId, "流不存在或已关闭");
        }

        sourceIndex.remove(holder.getSourceKey(), holder.getId());
        stopInternal(holder);
        return toResponse(holder, "STOPPED", "流已关闭");
    }

    private void stopInternal(StreamProcessHolder holder) {
        Process process = holder.getProcess();
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        deleteQuietly(holder.getStreamDir());
    }

    private String normalizeRtspUrl(String rtspUrl) {
        String normalizedUrl = rtspUrl == null ? "" : rtspUrl.trim();
        if (normalizedUrl.isEmpty()) {
            throw new IllegalArgumentException("RTSP 地址不能为空");
        }
        if (!normalizedUrl.startsWith("rtsp://")) {
            throw new IllegalArgumentException("当前仅支持 rtsp:// 开头的地址");
        }
        return normalizedUrl;
    }

    private List<String> buildProbeCommand(String rtspUrl) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutableResolver.resolveExecutable());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-rtsp_transport");
        command.add("tcp");
        command.add("-analyzeduration");
        command.add("1000000");
        command.add("-probesize");
        command.add("1000000");
        command.add("-i");
        command.add(rtspUrl);
        command.add("-map");
        command.add("0:v:0");
        command.add("-frames:v");
        command.add("1");
        command.add("-an");
        command.add("-f");
        command.add("null");
        command.add("-");
        return command;
    }

    private StreamProcessHolder getExistingHolder(String streamId) {
        StreamProcessHolder holder = holders.get(streamId);
        if (holder == null) {
            throw new IllegalArgumentException("流不存在");
        }
        return holder;
    }

    private Process startFfmpeg(String rtspUrl, Path playlist) throws IOException {
        int hlsTime = streamProperties.getHlsTimeSeconds();
        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutableResolver.resolveExecutable());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");
        // 输入侧：强制 TCP、socket 超时、快速探测，避免 RTSP 握手卡住或首段迟迟出不来
        command.add("-rtsp_transport");
        command.add("tcp");
        command.add("-fflags");
        command.add("+genpts+discardcorrupt");
        command.add("-analyzeduration");
        command.add("1000000");
        command.add("-probesize");
        command.add("1000000");
        command.add("-i");
        command.add(rtspUrl);
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a:0?");
        // 视频：HEVC/H.264 统一转 H.264 baseline-friendly main profile，yuv420p 供浏览器解码
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-tune");
        command.add("zerolatency");
        command.add("-profile:v");
        command.add("main");
        command.add("-level:v");
        command.add("4.1");
        command.add("-pix_fmt");
        command.add("yuv420p");
        // 强制关键帧对齐 hls_time，保证每个切片都是独立可解码段
        command.add("-force_key_frames");
        command.add("expr:gte(t,n_forced*" + hlsTime + ")");
        command.add("-sc_threshold");
        command.add("0");
        // 音频：若源无音轨则 -map 可选跳过；否则重采样到 44.1k 并做时钟补偿，避免音画不同步导致 hls.js 丢段
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-ar");
        command.add("44100");
        command.add("-ac");
        command.add("2");
        command.add("-af");
        command.add("aresample=async=1000");
        command.add("-f");
        command.add("hls");
        command.add("-hls_time");
        command.add(String.valueOf(hlsTime));
        command.add("-hls_list_size");
        command.add(String.valueOf(streamProperties.getHlsListSize()));
        command.add("-hls_segment_type");
        command.add("mpegts");
        // 去掉 append_list：该 flag 与新建目录 + delete_segments 组合会产生不一致的 m3u8
        command.add("-hls_flags");
        command.add("delete_segments+omit_endlist+independent_segments+program_date_time");
        command.add("-hls_segment_filename");
        command.add(playlist.getParent().resolve("segment_%05d.ts").toString());
        command.add(playlist.toString());

        log.info("Start ffmpeg for stream {}", playlist.getParent().getFileName());
        return new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(playlist.getParent().resolve("ffmpeg.log").toFile())
            .start();
    }

    private void waitUntilReady(StreamProcessHolder holder, Path playlist) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(streamProperties.getStartupTimeoutSeconds()));
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(playlist) && isPlaylistReady(playlist)) {
                holder.heartbeat();
                return;
            }
            if (!holder.getProcess().isAlive()) {
                String logTail = readLogTail(holder.getStreamDir().resolve("ffmpeg.log"));
                throw new IllegalStateException("ffmpeg 已退出，未生成可播放 HLS。日志：" + logTail);
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("等待 HLS 输出超时，请检查 RTSP 地址是否可访问");
    }

    private boolean isPlaylistReady(Path playlist) {
        try {
            String content = Files.readString(playlist);
            if (!content.contains("#EXTM3U") || !content.contains("#EXTINF")) {
                return false;
            }
            // 再确认首个切片已落盘且非空，避免浏览器拉到 0 字节的 ts
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(playlist.getParent(), "*.ts")) {
                for (Path ts : stream) {
                    if (Files.size(ts) > 0) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    private String readLogTail(Path logFile) {
        if (!Files.exists(logFile)) {
            return "未找到 ffmpeg 日志";
        }
        try {
            List<String> lines = Files.readAllLines(logFile);
            int fromIndex = Math.max(0, lines.size() - 8);
            return String.join(" | ", lines.subList(fromIndex, lines.size()));
        } catch (IOException ex) {
            return "读取 ffmpeg 日志失败：" + ex.getMessage();
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!output.isEmpty()) {
                    output.append(" | ");
                }
                output.append(line);
            }
            return output.toString();
        }
    }

    private String buildProbeFailureMessage(String output) {
        if (output == null || output.isBlank()) {
            return "RTSP 流离线或不可访问";
        }
        String normalized = output.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 180) {
            normalized = normalized.substring(0, 180) + "...";
        }
        return "RTSP 流离线或不可访问：" + normalized;
    }

    private Path resolveOutputRoot() {
        Path outputRoot = Paths.get(streamProperties.getOutputDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建 HLS 输出目录：" + outputRoot, ex);
        }
        return outputRoot;
    }

    private void deleteQuietly(Path target) {
        if (target == null || !Files.exists(target)) {
            return;
        }
        try {
            Files.walk(target)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        log.warn("Delete path failed: {}", path, ex);
                    }
                });
        } catch (IOException ex) {
            log.warn("Delete stream dir failed: {}", target, ex);
        }
    }

    private StreamResponse toResponse(StreamProcessHolder holder, String status, String message) {
        return new StreamResponse(
            holder.getId(),
            holder.getSourceKey(),
            holder.getRtspUrl(),
            holder.getPlayUrl(),
            status,
            holder.getViewerCount(),
            holder.getCreatedAt(),
            holder.getLastAccessAt(),
            message
        );
    }

    private String runtimeStatus(StreamProcessHolder holder) {
        if (!holder.getProcess().isAlive()) {
            return "EXITED";
        }
        if (holder.getViewerCount() == 0) {
            return "IDLE";
        }
        return "RUNNING";
    }

    private String statusMessage(StreamProcessHolder holder) {
        if (!holder.getProcess().isAlive()) {
            return "ffmpeg 进程已退出";
        }
        if (holder.getViewerCount() == 0) {
            return "当前无观看者，等待空闲回收";
        }
        return "转流运行中";
    }

    private StreamResponse notFound(String streamId, String message) {
        return new StreamResponse(streamId, null, null, null, "NOT_FOUND", 0, null, null, message);
    }

    @PreDestroy
    public synchronized void shutdown() {
        List<String> streamIds = new ArrayList<>(holders.keySet());
        for (String streamId : streamIds) {
            forceClose(streamId);
        }
        cleanupEmptyRoot();
    }

    private void cleanupEmptyRoot() {
        Path root = Paths.get(streamProperties.getOutputDir()).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            if (!stream.iterator().hasNext()) {
                Files.deleteIfExists(root);
            }
        } catch (IOException ex) {
            log.debug("Skip cleanup output root: {}", root, ex);
        }
    }
}
