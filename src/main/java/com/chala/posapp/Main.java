package com.chala.posapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching   // MISS-01: Caffeine cache
@EnableScheduling // MISS-05: @Scheduled nightly jobs
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class);
    }
}