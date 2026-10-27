package com.gnemirko.jobapplier.scoring.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** True when a learner token is configured; without one the learn endpoint does not exist. */
public class LearnEndpointCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String token = context.getEnvironment().getProperty("scoring.learn.token", "");
        return !token.isBlank();
    }
}
