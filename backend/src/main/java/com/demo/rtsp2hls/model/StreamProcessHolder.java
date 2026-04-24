package com.demo.rtsp2hls.model;

import java.nio.file.Path;
import java.time.Instant;

public class StreamProcessHolder {

    private final String id;
    private final String sourceKey;
    private final String rtspUrl;
    private final String playUrl;
    private final Path streamDir;
    private final Process process;
    private final Instant createdAt;
    private volatile Instant lastAccessAt;
    private volatile int viewerCount;

    public StreamProcessHolder(
        String id,
        String sourceKey,
        String rtspUrl,
        String playUrl,
        Path streamDir,
        Process process,
        Instant createdAt,
        Instant lastAccessAt,
        int viewerCount
    ) {
        this.id = id;
        this.sourceKey = sourceKey;
        this.rtspUrl = rtspUrl;
        this.playUrl = playUrl;
        this.streamDir = streamDir;
        this.process = process;
        this.createdAt = createdAt;
        this.lastAccessAt = lastAccessAt;
        this.viewerCount = viewerCount;
    }

    public String getId() {
        return id;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public Path getStreamDir() {
        return streamDir;
    }

    public Process getProcess() {
        return process;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessAt() {
        return lastAccessAt;
    }

    public int getViewerCount() {
        return viewerCount;
    }

    public void heartbeat() {
        lastAccessAt = Instant.now();
    }

    public void incrementViewer() {
        viewerCount += 1;
        heartbeat();
    }

    public void decrementViewer() {
        viewerCount = Math.max(0, viewerCount - 1);
        heartbeat();
    }
}
