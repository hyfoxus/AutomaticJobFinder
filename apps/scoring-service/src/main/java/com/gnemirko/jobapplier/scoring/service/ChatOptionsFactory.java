package com.gnemirko.jobapplier.scoring.service;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;

/** Builds the provider-specific request options for a given output cap. */
@Component
@ScoringEnabled
public class ChatOptionsFactory {

    private final String model;
    private final Double temperature;
    private final boolean openAi;
    private final boolean anthropic;

    public ChatOptionsFactory(ScoringProperties props, Environment env) {
        this.model = props.llm().model();
        this.temperature = props.llm().temperature();
        String provider = env.getProperty("spring.ai.model.chat", "none");
        this.openAi = "openai".equalsIgnoreCase(provider);
        this.anthropic = "anthropic".equalsIgnoreCase(provider);
    }

    /** OpenAI takes max-completion-tokens (max-tokens is rejected by newer models); others take max-tokens. */
    public ChatOptions options(int maxTokens) {
        if (openAi) {
            OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model(model).maxCompletionTokens(maxTokens);
            if (temperature != null) {
                b.temperature(temperature);
            }
            return b.build();
        }
        if (anthropic) {
            // Thinking would spend the whole output cap and leave an empty reply.
            AnthropicChatOptions.Builder b = AnthropicChatOptions.builder().model(model).maxTokens(maxTokens)
                    .thinkingDisabled();
            if (temperature != null) {
                b.temperature(temperature);
            }
            return b.build();
        }
        ChatOptions.Builder<?> b = ChatOptions.builder().model(model).maxTokens(maxTokens);
        if (temperature != null) {
            b.temperature(temperature);
        }
        return b.build();
    }
}
