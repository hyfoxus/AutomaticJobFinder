package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import com.gnemirko.jobapplier.scoring.service.ScoringService;

/** Defaults only: no provider, no profile, no keys. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ScoringDisabledTest {

    @Autowired ApplicationContext context;

    @Test
    void startsWithoutAnyScoringBeans() {
        assertThat(context.getBeanNamesForType(ScoringService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ChatModel.class)).isEmpty();
    }
}
