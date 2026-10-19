package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gnemirko.jobapplier.scoring.service.BudgetGuard;
import com.gnemirko.jobapplier.scoring.service.UsageLedger;

@SpringBootTest(properties = {
        "scoring.enabled=true",
        "scoring.profile-path=src/test/resources/profile.test.yml",
        "scoring.llm.model=test-model",
        "scoring.llm.free=true",
        "scoring.budget.daily-usd=0.05",
        "scoring.budget.zone=UTC"
})
@Import({TestcontainersConfiguration.class, ScoringTestConfig.class})
class ScoringLedgerTest extends ScoringTestSupport {

    @Autowired UsageLedger ledger;
    @Autowired BudgetGuard guard;

    @Test
    void daySpendIsTheSumOfThatDaysRowsAndResetsAtMidnight() {
        ledger.record("m", 1, 1, 10_000, false, "k1");
        ledger.record("m", 1, 1, 15_000, true, "k2");
        assertThat(ledger.spentToday()).isEqualTo(25_000);
        assertThat(guard.canAfford(25_000)).isTrue();
        assertThat(guard.canAfford(25_001)).isFalse();

        clock.advance(Duration.ofDays(1));
        assertThat(ledger.spentToday()).isZero();
        assertThat(guard.canAfford(50_000)).isTrue();
        assertThat(guard.canAfford(50_001)).isFalse();
    }

    @Test
    void stateReflectsEightyPercent() {
        assertThat(guard.isNearCap(39_999)).isFalse();
        assertThat(guard.isNearCap(40_000)).isTrue();
        assertThat(guard.capMicros()).isEqualTo(50_000);
    }

    @Test
    void concurrentWritersNeverLoseARow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int n = i;
            done.add(pool.submit(() -> {
                go.await();
                ledger.record("m", 1, 1, 100, false, "k" + n);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();
        assertThat(ledger.spentToday()).isEqualTo(4_000);
    }
}
