package com.demo.rtsp2hls.model;

import jakarta.validation.constraints.NotBlank;

public record OpenStreamRequest(@NotBlank(message = "rtspUrl 不能为空") String rtspUrl) {
}
