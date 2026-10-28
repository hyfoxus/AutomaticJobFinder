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
import com.gnemirko.jobapplier.ingestion.learner.dto.CloneRequest;
import com.gnemirko.jobapplier.ingestion.learner.dto.CloneStarted;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnBusyException;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnRejectedException;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeCloner;

import lombok.RequiredArgsConstructor;

/** Starts a clone run. The outcome is written to the review folder, not returned. */
@RestController
@LearnerEnabled
@RequestMapping(path = "/api/clone", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CloneController {

    private final RecipeCloner cloner;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> clone(
            @RequestHeader(value = LearnController.RUN_HEADER, required = false) String marker,
            @RequestBody CloneRequest req) {
        if (!"1".equals(marker)) {
            throw new LearnRejectedException("header " + LearnController.RUN_HEADER + ": 1 is required");
        }
        CloneStarted started = cloner.start(req == null ? null : req.template(), req == null ? null : req.indexUrl());
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
