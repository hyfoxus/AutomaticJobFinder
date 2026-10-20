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
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.scoring.source.IngestionClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.scoring.service.ScoringRunner.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.scoring.service.ScoringScheduler.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.scoring.controller.AssessmentController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.scoring.controller.UsageController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.scoring.controller.RunController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.scoring.config.ApiGuardFilter.class)).isEmpty();
    }
}
