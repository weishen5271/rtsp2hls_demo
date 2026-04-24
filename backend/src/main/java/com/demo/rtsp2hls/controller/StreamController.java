package com.demo.rtsp2hls.controller;

import com.demo.rtsp2hls.model.OpenStreamRequest;
import com.demo.rtsp2hls.model.RtspProbeRequest;
import com.demo.rtsp2hls.model.RtspProbeResponse;
import com.demo.rtsp2hls.model.StreamResponse;
import com.demo.rtsp2hls.service.StreamService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamService streamService;

    public StreamController(StreamService streamService) {
        this.streamService = streamService;
    }

    @PostMapping("/open")
    public StreamResponse open(@Valid @RequestBody OpenStreamRequest request) {
        return streamService.open(request.rtspUrl());
    }

    @PostMapping("/probe")
    public RtspProbeResponse probe(@Valid @RequestBody RtspProbeRequest request) {
        return streamService.probe(request.rtspUrl());
    }

    @PostMapping("/{streamId}/heartbeat")
    public StreamResponse heartbeat(@PathVariable String streamId) {
        return streamService.heartbeat(streamId);
    }

    @PostMapping("/{streamId}/release")
    public StreamResponse release(@PathVariable String streamId) {
        return streamService.release(streamId);
    }

    @DeleteMapping("/{streamId}")
    public StreamResponse close(@PathVariable String streamId) {
        return streamService.close(streamId);
    }

    @GetMapping("/{streamId}")
    public StreamResponse detail(@PathVariable String streamId) {
        return streamService.detail(streamId);
    }

    @GetMapping
    public List<StreamResponse> list() {
        return streamService.list();
    }
}
