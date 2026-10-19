package com.gnemirko.jobapplier.scoring.dto;

import org.springframework.ai.chat.prompt.Prompt;

/** A ready prompt and its rough size (characters / 4), used for the budget check. */
public record BuiltPrompt(Prompt prompt, long estimatedTokens) {
}
