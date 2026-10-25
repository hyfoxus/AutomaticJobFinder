package com.gnemirko.jobapplier.ui.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@UiEnabled
@EnableConfigurationProperties(UiProperties.class)
public class UiConfig {

    @Bean
    Clock uiClock() {
        return Clock.systemUTC();
    }
}
