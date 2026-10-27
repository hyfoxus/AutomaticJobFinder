package com.gnemirko.jobapplier.scoring.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/** Marks a bean that exists only when scoring is enabled and a learner token is configured. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ScoringEnabled
@Conditional(LearnEndpointCondition.class)
public @interface LearnEnabled {
}
