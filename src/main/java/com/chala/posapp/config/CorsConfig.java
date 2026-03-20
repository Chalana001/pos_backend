package com.chala.posapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(
                                "http://localhost:3000", // Localhost (React/CRA)
                                "http://localhost:5173", // Localhost (Vite) - ඔයාට ඕන නම් තියාගන්න
                                "https://pos-frontend-blue-three.vercel.app",
                                "https://pos-frontend-2uxryhvgy-chalana001s-projects.vercel.app"// 🚀 ඔයාගේ Live Vercel Domain එක
                        )
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
