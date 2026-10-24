package com.gnemirko.jobapplier.scoring.config;

import java.nio.file.Path;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Configuration;

import com.gnemirko.jobapplier.scoring.dto.Profile;
import com.gnemirko.jobapplier.scoring.exception.ScoringConfigurationException;
import com.gnemirko.jobapplier.scoring.util.ProfileLoader;

import lombok.extern.slf4j.Slf4j;

/** Beans that exist only when scoring is enabled. Every misconfiguration stops startup with a clear message. */
@Slf4j
@Configuration
@ScoringEnabled
@EnableScheduling
public class ScoringConfig {

    @Bean
    ScoringSettingsCheck scoringSettingsCheck(ScoringProperties props, ApiProperties api, Environment env) {
        check(props);
        api.normalizedHosts();
        String provider = env.getProperty("spring.ai.model.chat", "none");
        if (props.llm().free() && ("openai".equalsIgnoreCase(provider) || "anthropic".equalsIgnoreCase(provider))) {
            throw new ScoringConfigurationException("scoring.llm.free=true is only allowed for a local provider "
                    + "(ollama); provider '" + provider + "' bills per token. Set scoring prices instead.");
        }
        return new ScoringSettingsCheck();
    }

    @Bean
    Profile profile(ScoringProperties props, ScoringSettingsCheck check) {
        Profile profile = ProfileLoader.load(Path.of(props.profilePath()));
        log.info("Profile loaded from {}: {} must-haves", props.profilePath(), profile.mustHaves().size());
        return profile;
    }

    @Bean
    ChatClient chatClient(ObjectProvider<ChatModel> chatModel, ScoringSettingsCheck check) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            throw new ScoringConfigurationException("scoring.enabled=true but no AI provider is configured. "
                    + "Set SCORING_AI_PROVIDER (spring.ai.model.chat) to openai, anthropic or ollama, with its key or URL.");
        }
        return ChatClient.create(model);
    }

    static void check(ScoringProperties props) {
        if (props.llm().model() == null || props.llm().model().isBlank()) {
            throw new ScoringConfigurationException("scoring.llm.model must name the model to call");
        }
        if (props.llm().maxTokens() <= 0) {
            throw new ScoringConfigurationException("scoring.llm.max-tokens must be greater than 0");
        }
        if (props.llm().maxDescriptionChars() <= 0) {
            throw new ScoringConfigurationException("scoring.llm.max-description-chars must be greater than 0");
        }
        if (props.maxAttempts() < 1) {
            throw new ScoringConfigurationException("scoring.max-attempts must be at least 1");
        }
        if (props.budget().dailyUsd() == null || props.budget().dailyUsd().signum() <= 0) {
            throw new ScoringConfigurationException("scoring.budget.daily-usd must be greater than 0");
        }
        props.budget().zoneId();
        props.ingestion().validate();
    }

    /** Marker so beans can depend on the settings having been checked first. */
    static final class ScoringSettingsCheck {
    }
}
