package com.gnemirko.jobapplier.ingestion.learner.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.learner.dto.LearnRequest;
import com.gnemirko.jobapplier.ingestion.learner.dto.LearnStarted;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnBusyException;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnRejectedException;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeLearner;

import lombok.RequiredArgsConstructor;

/** Starts a learn run. The outcome is written to the review folder, not returned. */
@RestController
@LearnerEnabled
@RequestMapping(path = "/api/learn", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LearnController {

    public static final String RUN_HEADER = "X-JobApplier-Run";

    private final RecipeLearner learner;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> learn(@RequestHeader(value = RUN_HEADER, required = false) String marker,
            @RequestBody LearnRequest req) {
        if (!"1".equals(marker)) {
            throw new LearnRejectedException("header " + RUN_HEADER + ": 1 is required");
        }
        LearnStarted started = learner.start(req == null ? null : req.url());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
    }

    @ExceptionHandler(LearnRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(LearnRejectedException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(LearnBusyException.class)
    ResponseEntity<Map<String, String>> busy(LearnBusyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
