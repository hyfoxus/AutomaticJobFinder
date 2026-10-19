package com.gnemirko.jobapplier.scoring.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.dto.Assessment;
import com.gnemirko.jobapplier.scoring.dto.BuiltPrompt;
import com.gnemirko.jobapplier.scoring.dto.CallResult;
import com.gnemirko.jobapplier.scoring.dto.CallResult.Kind;
import com.gnemirko.jobapplier.scoring.dto.Gap;
import com.gnemirko.jobapplier.scoring.dto.Profile;
import com.gnemirko.jobapplier.scoring.util.ProviderErrors;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.json.JsonMapper;

/**
 * One model call and the checking of its reply. The reply is read through {@code chatResponse()}
 * so tokens are known even when conversion fails. Never throws: every failure becomes a result.
 */
@Slf4j
@Component
@ScoringEnabled
public class FitAssessor {

    static final int MAX_GAP_CHARS = 120;
    static final int MAX_REASON_CHARS = 300;
    static final int MAX_GAPS = 20;

    private final ChatClient chatClient;
    private final int mustHaveCount;
    private final BeanOutputConverter<Assessment> converter = new BeanOutputConverter<>(Assessment.class,
            JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    // Text fields accept only JSON strings; numbers and booleans are not turned into text.
                    .withCoercionConfig(LogicalType.Textual, c -> c
                            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                    .withCoercionConfig(LogicalType.Integer, c -> c
                            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
                    .build());

    public FitAssessor(ChatClient chatClient, Profile profile) {
        this.chatClient = chatClient;
        this.mustHaveCount = profile.mustHaves().size();
    }

    String formatInstructions() {
        return converter.getFormat();
    }

    public CallResult assess(BuiltPrompt built) {
        ChatResponse response;
        try {
            response = chatClient.prompt(built.prompt()).call().chatResponse();
        } catch (RuntimeException e) {
            return failure(e, built);
        }
        String text = textOf(response);
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        long in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        boolean estimated = false;
        if (in <= 0) {
            in = built.estimatedTokens();
            estimated = true;
        }
        if (out <= 0) {
            out = PromptBuilder.estimateTokens(text.length());
            estimated = true;
        }
        Assessment valid = null;
        String problem = null;
        try {
            valid = sanitize(converter.convert(text));
            if (valid == null) {
                problem = "reply missing required fields";
            }
        } catch (RuntimeException e) {
            problem = "reply does not match the schema: " + e.getClass().getSimpleName();
        }
        if (valid == null) {
            log.warn("Invalid model reply: {}", problem);
            return new CallResult(Kind.INVALID, null, in, out, estimated, true, problem);
        }
        return new CallResult(Kind.VALID, valid, in, out, estimated, true, null);
    }

    private CallResult failure(RuntimeException e, BuiltPrompt built) {
        return switch (ProviderErrors.classify(e)) {
            case AUTH -> new CallResult(Kind.AUTH_FAILURE, null, 0, 0, false, false, summary(e));
            case REJECTED -> new CallResult(Kind.REQUEST_REJECTED, null, 0, 0, false, false, summary(e));
            // A timed-out request may still have been billed for its prompt.
            case TIMEOUT -> new CallResult(Kind.TIMEOUT, null, built.estimatedTokens(), 0, true, true, summary(e));
            case OTHER -> new CallResult(Kind.PROVIDER_FAILURE, null, 0, 0, false, false, summary(e));
        };
    }

    private static String summary(RuntimeException e) {
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String textOf(ChatResponse r) {
        if (r == null || r.getResult() == null || r.getResult().getOutput() == null
                || r.getResult().getOutput().getText() == null) {
            return "";
        }
        return r.getResult().getOutput().getText();
    }

    /** Checks required fields and caps stored text. Returns null when the reply is unusable. */
    Assessment sanitize(Assessment a) {
        if (a == null || a.tier() == null || a.interviewChance() == null
                || a.reason() == null || a.reason().isBlank()) {
            return null;
        }
        Set<Integer> met = new LinkedHashSet<>();
        if (a.metMustHaves() != null) {
            a.metMustHaves().stream()
                    .filter(i -> i != null && i >= 1 && i <= mustHaveCount)
                    .sorted()
                    .forEach(met::add);
        }
        List<Gap> gaps = new ArrayList<>();
        if (a.gaps() != null) {
            for (Gap g : a.gaps()) {
                if (g == null || g.kind() == null || g.text() == null || g.text().isBlank()) {
                    return null;
                }
                if (gaps.size() < MAX_GAPS) {
                    gaps.add(new Gap(oneLine(g.text(), MAX_GAP_CHARS), g.kind()));
                }
            }
        }
        return new Assessment(a.tier(), List.copyOf(met), List.copyOf(gaps), a.interviewChance(),
                oneLine(a.reason(), MAX_REASON_CHARS));
    }

    private static String oneLine(String s, int max) {
        String t = s.replaceAll("\\s+", " ").strip();
        if (t.length() > max) {
            int end = max;
            if (Character.isHighSurrogate(t.charAt(end - 1))) {
                end--;
            }
            t = t.substring(0, end);
        }
        return t;
    }
}
