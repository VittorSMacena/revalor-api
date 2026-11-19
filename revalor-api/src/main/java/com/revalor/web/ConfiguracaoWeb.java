package com.revalor.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Libera CORS para a API, de modo que protótipos externos (Figma, Lovable, Bolt etc.) consigam chamá-la. */
@Configuration
public class ConfiguracaoWeb implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("GET", "POST", "DELETE", "OPTIONS");
    }
}
