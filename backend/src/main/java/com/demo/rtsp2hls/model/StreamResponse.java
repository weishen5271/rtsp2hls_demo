package com.demo.rtsp2hls.model;

import java.time.Instant;

public record StreamResponse(
    String id,
    String sourceKey,
    String rtspUrl,
    String playUrl,
    String status,
    int viewerCount,
    Instant createdAt,
    Instant lastAccessAt,
    String message
) {
}
