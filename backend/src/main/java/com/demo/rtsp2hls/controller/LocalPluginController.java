package com.demo.rtsp2hls.controller;

import com.demo.rtsp2hls.model.StreamResponse;
import com.demo.rtsp2hls.service.FfmpegExecutableResolver;
import com.demo.rtsp2hls.service.StreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocalPluginController {

    private final StreamService streamService;
    private final FfmpegExecutableResolver ffmpegExecutableResolver;

    public LocalPluginController(StreamService streamService, FfmpegExecutableResolver ffmpegExecutableResolver) {
        this.streamService = streamService;
        this.ffmpegExecutableResolver = ffmpegExecutableResolver;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "rtsp-hls-local-plugin");
        body.put("ffmpegExecutable", ffmpegExecutableResolver.resolveExecutable());
        body.put("activeStreams", streamService.list().size());
        body.put("bundledFfmpeg", true);
        return body;
    }

    @PostMapping("/plugin/init")
    public Map<String, Object> init(@RequestBody(required = false) PluginInitRequest request) {
        String executable = ffmpegExecutableResolver.resolveExecutable();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "插件初始化完成");
        body.put("ffmpegExecutable", executable);
        body.put("layout", request == null || request.layout() == null ? 1 : request.layout());
        body.put("bundledFfmpeg", true);
        body.put("requestedFfmpegExecutable", request == null ? null : request.ffmpegExecutable());
        body.put("requestedOutputRoot", request == null ? null : request.outputRoot());
        return body;
    }

    @PostMapping("/plugin/preview/start")
    public StreamResponse startPreview(@Valid @RequestBody PluginStartPreviewRequest request) {
        return streamService.open(request.rtspUrl());
    }

    @PostMapping("/plugin/preview/stop")
    public Map<String, Object> stopPreview(@RequestBody(required = false) PluginStopPreviewRequest request) {
        String streamId = request == null ? null : request.streamId();
        if (streamId == null || streamId.isBlank()) {
            return Map.of("message", "当前无活跃预览");
        }

        StreamResponse response = streamService.close(streamId);
        return Map.of(
            "message", response.message(),
            "streamId", response.id()
        );
    }

    @PostMapping("/plugin/preview/stop-all")
    public Map<String, Object> stopAllPreview() {
        List<StreamResponse> streams = streamService.list();
        for (StreamResponse stream : streams) {
            streamService.close(stream.id());
        }
        return Map.of("message", "所有本地插件预览已停止");
    }

    public record PluginInitRequest(
        String ffmpegExecutable,
        String outputRoot,
        Integer layout
    ) {
    }

    public record PluginStartPreviewRequest(
        @NotBlank(message = "rtspUrl 不能为空") String rtspUrl
    ) {
    }

    public record PluginStopPreviewRequest(String streamId) {
    }
}
