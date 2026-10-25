package com.gnemirko.jobapplier.ui;

import static com.gnemirko.jobapplier.ui.UiTestSupport.goodRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Each test gets a fresh context, so each starts outside any cooldown. */
@SpringBootTest(properties = {"ui.enabled=true", "ui.run-cooldown=60s", "ui.scoring.read-timeout=1s"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RunCooldownTest {

    static final StubScoring STUB = new StubScoring();

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void scoring(DynamicPropertyRegistry registry) {
        registry.add("ui.scoring.base-url", STUB::baseUrl);
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    private void click(UiTestSupport.Token t, String msg) throws Exception {
        mvc.perform(goodRun(t)).andExpect(status().isSeeOther()).andExpect(header().string("Location", "/?msg=" + msg));
    }

    @Test
    void secondClickInsideTheCooldownIsBusyWithoutCallingScoring() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        click(t, "started");
        click(t, "busy");
        assertThat(STUB.runCalls.get()).isEqualTo(1);
    }

    @Test
    void cooldownAlsoFollowsAFailedAttempt() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        STUB.run.delayMs = 2500; // read timeout is 1 s: a timeout
        click(t, "failed");
        STUB.run.delayMs = 0;
        click(t, "busy");
        assertThat(STUB.runCalls.get()).isEqualTo(1);
    }

    @Test
    void cooldownFollowsAnErrorStatusToo() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        STUB.run.status = 500;
        click(t, "failed");
        STUB.run.status = 202;
        click(t, "busy");
        assertThat(STUB.runCalls.get()).isEqualTo(1);
    }

    @Test
    void cooldownFollowsABusyAnswer() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        STUB.run.status = 409;
        click(t, "busy");
        STUB.run.status = 202;
        click(t, "busy");
        assertThat(STUB.runCalls.get()).isEqualTo(1);
    }
}
