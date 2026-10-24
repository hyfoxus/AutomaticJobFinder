package com.gnemirko.jobapplier.digest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import com.gnemirko.jobapplier.digest.service.DigestBuilder;
import com.gnemirko.jobapplier.digest.service.DigestMailer;
import com.gnemirko.jobapplier.digest.service.DigestRunner;
import com.gnemirko.jobapplier.digest.service.DigestScheduler;
import com.gnemirko.jobapplier.digest.source.ScoringApiClient;

/** Default settings: the context starts and has no digest beans. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DigestDisabledTest {

    @Autowired ApplicationContext ctx;

    @Test
    void noBeanFromTheDigestPackagesExistsExceptSettingsAndClock() {
        for (String name : ctx.getBeanDefinitionNames()) {
            Class<?> type = ctx.getType(name) == null ? null : org.springframework.util.ClassUtils.getUserClass(ctx.getType(name));
            if (type == null || !type.getName().startsWith("com.gnemirko.jobapplier.digest.")) {
                continue;
            }
            boolean allowed = type == com.gnemirko.jobapplier.digest.config.DigestProperties.class
                    || type == com.gnemirko.jobapplier.digest.config.ClockConfig.class
                    || type == DigestServiceApplication.class
                    || type == TestcontainersConfiguration.class
                    || type == DigestDisabledTest.class
                    || type.getName().startsWith("com.gnemirko.jobapplier.digest.repository.");
            assertThat(allowed).as("unexpected bean %s of %s", name, type.getName()).isTrue();
        }
        for (Class<?> type : new Class<?>[] {com.gnemirko.jobapplier.digest.config.DigestConfig.class,
                com.gnemirko.jobapplier.digest.config.DigestConfig.DigestSettingsCheck.class,
                com.gnemirko.jobapplier.digest.service.ScoringStartupReport.class,
                com.gnemirko.jobapplier.digest.service.SmtpMailer.class,
                com.gnemirko.jobapplier.digest.service.DryRunMailer.class}) {
            assertThat(ctx.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
        }
    }

    @Test
    void startsWithoutDigestBeans() {
        for (Class<?> type : new Class<?>[] {DigestRunner.class, DigestMailer.class, DigestBuilder.class,
                DigestScheduler.class, ScoringApiClient.class}) {
            assertThat(ctx.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
        }
    }
}
