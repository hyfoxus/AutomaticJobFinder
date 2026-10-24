package com.gnemirko.jobapplier.scoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.gnemirko.jobapplier.scoring.FakeChatModel;
import com.gnemirko.jobapplier.scoring.config.ScoringConfig;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;

/** Misconfiguration must stop startup with a readable message. No database involved. */
class StartupFailureTest {

    private static final String PROFILE = "src/test/resources/profile.test.yml";

    @Configuration
    @EnableConfigurationProperties({ScoringProperties.class, com.gnemirko.jobapplier.scoring.config.ApiProperties.class})
    @Import(ScoringConfig.class)
    static class Base {
    }

    @Configuration
    static class WithModel {
        @Bean
        ChatModel chatModel() {
            return new FakeChatModel();
        }
    }

    private ApplicationContextRunner runner(String... props) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Base.class, CostModel.class)
                .withPropertyValues("scoring.enabled=true", "scoring.profile-path=" + PROFILE,
                        "scoring.llm.model=m", "scoring.llm.prices[m].input-per-million=1",
                        "scoring.llm.prices[m].output-per-million=1")
                .withPropertyValues(props);
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void validSettingsStart() {
        runner().withUserConfiguration(WithModel.class).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void providerNoneFailsFast() {
        runner("spring.ai.model.chat=none").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("no AI provider");
        });
    }

    @Test
    void paidModelWithoutPricesFailsFast() {
        runner("scoring.llm.model=other").withUserConfiguration(WithModel.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("has no prices");
        });
    }

    @Test
    void freeModelWithABilledProviderFailsFast() {
        for (String provider : new String[] {"openai", "anthropic"}) {
            runner("scoring.llm.free=true", "spring.ai.model.chat=" + provider).withUserConfiguration(WithModel.class)
                    .run(ctx -> {
                        assertThat(ctx).hasFailed();
                        assertThat(rootMessage(ctx.getStartupFailure())).contains("scoring.llm.free=true");
                    });
        }
        runner("scoring.llm.free=true", "spring.ai.model.chat=ollama").withUserConfiguration(WithModel.class)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void freeModelNeedsNoPrices() {
        runner("scoring.llm.model=llama3", "scoring.llm.free=true").withUserConfiguration(WithModel.class)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void nonPositiveCapFailsFast() {
        for (String cap : new String[] {"0", "-1"}) {
            runner("scoring.budget.daily-usd=" + cap).withUserConfiguration(WithModel.class).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(rootMessage(ctx.getStartupFailure())).contains("daily-usd");
            });
        }
    }

    @Test
    void missingProfileFailsFast() {
        runner("scoring.profile-path=/no/such/profile.yml").withUserConfiguration(WithModel.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("Profile file not found");
        });
    }

    @Test
    void badProfilesFailFast(@TempDir Path dir) throws Exception {
        assertProfileFails(dir, "skillsSummary: s\\nmustHaves: []\\n", "mustHaves");
        assertProfileFails(dir, "skillsSummary: s\\nmustHaves: [a]\\nfilters:\\n  excludeTitleRegex: ['(']\\n", "invalid regex");
        assertProfileFails(dir, "skillsSummary: s\\nmustHaves: [a]\\nfullName: Jane\\n", "fullName");
    }

    private void assertProfileFails(Path dir, String yaml, String expected) throws Exception {
        Path file = Files.createTempFile(dir, "profile", ".yml");
        Files.writeString(file, yaml.replace("\\n", "\n"));
        runner("scoring.profile-path=" + file).withUserConfiguration(WithModel.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains(expected);
        });
    }

    @Test
    void disabledCreatesNothing() {
        new ApplicationContextRunner()
                .withUserConfiguration(Base.class)
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean("profile"));
    }

    @Test
    void badAllowedHostsFailFast() {
        for (String bad : new String[] {"*", "a:8082"}) {
            runner("scoring.api.allowed-hosts=" + bad).withUserConfiguration(WithModel.class).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(rootMessage(ctx.getStartupFailure())).contains("allowed-hosts");
            });
        }
    }

    @Test
    void badIngestionSettingsFailFast() {
        String[][] cases = {
                {"scoring.ingestion.overlap=0s", "overlap"},
                {"scoring.ingestion.overlap=-5m", "overlap"},
                {"scoring.ingestion.chunk-size=0", "chunk-size"},
                {"scoring.ingestion.max-bytes=0", "max-bytes"}};
        for (String[] c : cases) {
            runner(c[0]).withUserConfiguration(WithModel.class).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(rootMessage(ctx.getStartupFailure())).contains(c[1]);
            });
        }
    }
}
