package com.chala.posapp.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.TimeZone;

@Component
public class ApplicationTimeZoneConfig {

    private static final String APP_TIME_ZONE = "Asia/Colombo";

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone(APP_TIME_ZONE));
    }
}
