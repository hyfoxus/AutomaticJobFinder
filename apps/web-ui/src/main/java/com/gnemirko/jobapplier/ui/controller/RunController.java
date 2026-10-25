package com.gnemirko.jobapplier.ui.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

import com.gnemirko.jobapplier.ui.config.UiEnabled;
import com.gnemirko.jobapplier.ui.dto.RunOutcome;
import com.gnemirko.jobapplier.ui.service.RunService;

import lombok.RequiredArgsConstructor;

/** The only write. The CSRF and Origin checks run in {@code CsrfFilter} before this is reached. */
@Controller
@UiEnabled
@RequiredArgsConstructor
public class RunController {

    private final RunService runs;

    @PostMapping("/run")
    public ResponseEntity<Void> run() {
        RunOutcome outcome = runs.trigger();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/?msg=" + outcome.code())).build();
    }
}
