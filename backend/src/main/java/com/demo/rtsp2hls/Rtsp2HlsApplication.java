package com.demo.rtsp2hls;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Rtsp2HlsApplication {

    public static void main(String[] args) {
        SpringApplication.run(Rtsp2HlsApplication.class, args);
    }
}
