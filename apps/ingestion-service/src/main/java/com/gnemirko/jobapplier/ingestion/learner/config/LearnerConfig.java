package com.gnemirko.jobapplier.ingestion.learner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the learner settings, only when the learner is switched on. */
@Configuration
@LearnerEnabled
@EnableConfigurationProperties(LearnerProperties.class)
public class LearnerConfig {
}
