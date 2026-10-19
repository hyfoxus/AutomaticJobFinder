package com.gnemirko.jobapplier.scoring;

import java.time.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import org.springframework.ai.chat.model.ChatModel;

@TestConfiguration(proxyBeanMethods = false)
public class ScoringTestConfig {

    @Bean
    @Primary
    FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
