package com.gnemirko.jobapplier.ingestion.learner.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Marks a bean that exists only when {@code ingestion.learner.enabled=true}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(prefix = "ingestion.learner", name = "enabled", havingValue = "true")
public @interface LearnerEnabled {
}
