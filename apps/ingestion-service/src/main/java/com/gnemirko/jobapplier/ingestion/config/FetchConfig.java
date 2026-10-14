package com.gnemirko.jobapplier.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gnemirko.jobapplier.ingestion.util.Sleeper;

@Configuration
@EnableConfigurationProperties(FetchProperties.class)
public class FetchConfig {

    @Bean
    Sleeper sleeper() {
        return Sleeper.SYSTEM;
    }
}
