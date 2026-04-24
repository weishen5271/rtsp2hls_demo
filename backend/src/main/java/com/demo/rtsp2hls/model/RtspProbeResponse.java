package com.demo.rtsp2hls.model;

import java.time.Instant;

public record RtspProbeResponse(
    String rtspUrl,
    boolean online,
    String status,
    Instant checkedAt,
    long elapsedMillis,
    String message
) {
}
