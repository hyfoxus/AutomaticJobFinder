package com.gnemirko.jobapplier.scoring.controller;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gnemirko.jobapplier.scoring.config.LearnEnabled;
import com.gnemirko.jobapplier.scoring.config.LearnProperties;
import com.gnemirko.jobapplier.scoring.dto.LearnRequest;
import com.gnemirko.jobapplier.scoring.exception.BadRequestException;
import com.gnemirko.jobapplier.scoring.exception.PayloadTooLargeException;
import com.gnemirko.jobapplier.scoring.service.CompletionService;

/**
 * Narrow model access for the recipe learner only: no prompt text from the caller, a fixed purpose, a
 * shared-secret token, input caps and the shared daily cap. Not a chat endpoint.
 */
@RestController
@LearnEnabled
@RequestMapping(path = "/api/llm", produces = MediaType.APPLICATION_JSON_VALUE)
public class LlmCompleteController {

    public static final String TOKEN_HEADER = "X-Learner-Token";
    private static final Pattern HOST = Pattern.compile("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$");

    private final CompletionService service;
    private final LearnProperties learn;
    private static final tools.jackson.databind.json.JsonMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();

    public LlmCompleteController(CompletionService service, LearnProperties learn) {
        this.service = service;
        this.learn = learn;
    }

    @PostMapping(path = "/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> complete(HttpServletRequest http) throws IOException {
        // Token, run header and Content-Length were already checked by LearnGuardInterceptor, before any parsing.
        long bodyCap = bodyCap(learn);
        byte[] raw = http.getInputStream().readNBytes((int) bodyCap + 1);
        if (raw.length > bodyCap) {
            throw new PayloadTooLargeException("request is too large");
        }
        LearnRequest req;
        try {
            req = MAPPER.readValue(raw, LearnRequest.class);
        } catch (RuntimeException e) {
            throw new BadRequestException("invalid request body");
        }
        if (req == null || req.host() == null || !HOST.matcher(req.host()).matches()) {
            throw new BadRequestException("host must be a lower-case host name");
        }
        if (req.skeleton() == null || req.skeleton().isBlank()) {
            throw new BadRequestException("skeleton is required");
        }
        if (req.skeleton().length() > learn.maxSkeletonChars()) {
            throw new PayloadTooLargeException("skeleton is over " + learn.maxSkeletonChars() + " characters");
        }
        if (req.feedback() != null && req.feedback().length() > learn.maxFeedbackChars()) {
            throw new PayloadTooLargeException("feedback is over " + learn.maxFeedbackChars() + " characters");
        }
        CompletionService.Outcome out = service.complete(req);
        return switch (out.status()) {
            case OK -> ResponseEntity.ok(out.reply());
            case CAP_REACHED -> error(HttpStatus.PAYMENT_REQUIRED, "daily budget reached");
            case LEARN_LIMIT -> error(HttpStatus.TOO_MANY_REQUESTS, "daily learn limit reached");
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "model timed out");
            case UNAVAILABLE -> error(HttpStatus.BAD_GATEWAY, "model unavailable");
        };
    }

    /** JSON escaping can make the body longer than the text, so allow a generous multiple. */
    public static long bodyCap(LearnProperties learn) {
        return 8L * (learn.maxSkeletonChars() + learn.maxFeedbackChars()) + 4096;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
