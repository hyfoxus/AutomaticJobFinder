package com.gnemirko.jobapplier.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** UI enabled against a stub scoring on loopback; no cooldown, short timeouts. */
@SpringBootTest(properties = {
        "ui.enabled=true",
        "ui.run-cooldown=0s",
        "ui.scoring.read-timeout=1s",
        "ui.scoring.max-bytes=200000"
})
@AutoConfigureMockMvc
abstract class WebUiBase {

    static final StubScoring STUB = new StubScoring();

    @DynamicPropertySource
    static void scoring(DynamicPropertyRegistry registry) {
        registry.add("ui.scoring.base-url", STUB::baseUrl);
    }

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    @AfterAll
    static void noop() {
        // The stub lives for the whole test run; the JVM ends it.
    }
}
