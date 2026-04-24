package com.demo.rtsp2hls.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final StreamProperties streamProperties;

    public WebConfig(StreamProperties streamProperties) {
        this.streamProperties = streamProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path outputDir = Paths.get(streamProperties.getOutputDir()).toAbsolutePath().normalize();
        registry.addResourceHandler("/hls/**")
            .addResourceLocations(outputDir.toUri().toString() + "/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders("*");
    }
}
