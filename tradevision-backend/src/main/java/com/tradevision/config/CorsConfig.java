package com.tradevision.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        // Support comma-separated origins
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS","PATCH"));
        cfg.setAllowedHeaders(List.of("*"));
        // Review finding ("CorsConfig still exposes Authorization" -- external review,
        // twenty-fourth pass, P2, confirmed real by direct inspection before this fix: this
        // application moved to HttpOnly-cookie authentication, and a repo-wide search found no
        // frontend code anywhere that reads an Authorization response header -- exposing it to
        // browser JS was unnecessary transport surface for a header no longer used as a real
        // authentication mechanism): removed entirely. If a genuine external API consumer ever
        // needs to read this header from a response, it can be re-added deliberately then, with
        // that actual need stated.
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return new CorsFilter(src);
    }
}
