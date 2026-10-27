package com.gnemirko.jobapplier.scoring.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@LearnEnabled
public class LearnWebConfig implements WebMvcConfigurer {

    private final LearnGuardInterceptor guard;

    public LearnWebConfig(LearnGuardInterceptor guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/llm/complete");
    }
}
