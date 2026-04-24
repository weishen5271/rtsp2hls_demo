package com.demo.rtsp2hls.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.stream")
public class StreamProperties {

    private String ffmpegExecutable = "ffmpeg";
    private String bundledFfmpegRoot = "ffmpeg";
    private String bundledFfmpegOutputDir = "runtime/bin";
    private String outputDir = "runtime/hls";
    private int hlsTimeSeconds = 2;
    private int hlsListSize = 6;
    private int startupTimeoutSeconds = 15;
    private int heartbeatIntervalSeconds = 15;
    private int idleTimeoutSeconds = 30;
    private int cleanupIntervalSeconds = 15;

    public String getFfmpegExecutable() {
        return ffmpegExecutable;
    }

    public void setFfmpegExecutable(String ffmpegExecutable) {
        this.ffmpegExecutable = ffmpegExecutable;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public String getBundledFfmpegRoot() {
        return bundledFfmpegRoot;
    }

    public void setBundledFfmpegRoot(String bundledFfmpegRoot) {
        this.bundledFfmpegRoot = bundledFfmpegRoot;
    }

    public String getBundledFfmpegOutputDir() {
        return bundledFfmpegOutputDir;
    }

    public void setBundledFfmpegOutputDir(String bundledFfmpegOutputDir) {
        this.bundledFfmpegOutputDir = bundledFfmpegOutputDir;
    }

    public int getHlsTimeSeconds() {
        return hlsTimeSeconds;
    }

    public void setHlsTimeSeconds(int hlsTimeSeconds) {
        this.hlsTimeSeconds = hlsTimeSeconds;
    }

    public int getHlsListSize() {
        return hlsListSize;
    }

    public void setHlsListSize(int hlsListSize) {
        this.hlsListSize = hlsListSize;
    }

    public int getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    public void setStartupTimeoutSeconds(int startupTimeoutSeconds) {
        this.startupTimeoutSeconds = startupTimeoutSeconds;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public int getCleanupIntervalSeconds() {
        return cleanupIntervalSeconds;
    }

    public void setCleanupIntervalSeconds(int cleanupIntervalSeconds) {
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
    }
}
