package com.gnemirko.jobapplier.scoring.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.exception.BadRequestException;
import com.gnemirko.jobapplier.scoring.service.ScoringRunner;

import lombok.RequiredArgsConstructor;

@RestController
@ScoringEnabled
@RequiredArgsConstructor
@RequestMapping(path = "/api/scoring", produces = MediaType.APPLICATION_JSON_VALUE)
public class RunController {

    public static final String HEADER = "X-JobApplier-Run";

    private final ScoringRunner runner;

    /** Starts a run in the background. The custom header keeps a web page on another origin from triggering it. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> run(@RequestHeader(value = HEADER, required = false) String marker) {
        if (!"1".equals(marker)) {
            throw new BadRequestException("header " + HEADER + ": 1 is required");
        }
        if (!runner.startAsync()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "busy"));
        }
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }
}
