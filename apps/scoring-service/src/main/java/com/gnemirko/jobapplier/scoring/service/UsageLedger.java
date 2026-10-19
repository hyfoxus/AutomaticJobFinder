package com.gnemirko.jobapplier.scoring.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;
import com.gnemirko.jobapplier.scoring.entity.LlmUsageEntity;
import com.gnemirko.jobapplier.scoring.repository.LlmUsageRepository;

/**
 * One row per model call. A day's spend is the SUM of that day's rows, so a new day starts at zero
 * by itself. Every read and write takes one Postgres advisory lock. A call is reserved (check and
 * insert in one transaction), then settled with its real usage or released. A crash between the two
 * leaves the reservation in place, which errs on the safe side.
 */
@Component
@ScoringEnabled
public class UsageLedger {

    /** Arbitrary constant shared by every service that spends through this ledger. */
    static final long LOCK_KEY = 0x4A6F624170706C79L;

    private final LlmUsageRepository repository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;

    public UsageLedger(LlmUsageRepository repository, JdbcTemplate jdbc, PlatformTransactionManager txManager,
                       Clock clock, ScoringProperties props) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.zone = props.budget().zoneId();
    }

    public LocalDate today() {
        return clock.instant().atZone(zone).toLocalDate();
    }

    /** Micro-dollars spent so far today. */
    public long spentToday() {
        return tx.execute(status -> {
            lock();
            return repository.sumMicrosForDay(today());
        });
    }

    /** Check only, under the lock. Use {@link #reserve} before a call so the check and the charge are one step. */
    public boolean spentPlusFits(long extraMicros, long capMicros) {
        return tx.execute(status -> {
            lock();
            return repository.sumMicrosForDay(today()) + extraMicros <= capMicros;
        });
    }

    /**
     * Check and charge in one transaction under the advisory lock: when today's spend plus the worst
     * case fits the cap, a row for the worst case is inserted at once, so nobody else sees that money
     * as free while the call runs. Returns the row id, or empty when it does not fit.
     */
    public java.util.OptionalLong reserve(String model, long worstCaseMicros, long capMicros, String jobKey) {
        Long id = tx.execute(status -> {
            lock();
            if (repository.sumMicrosForDay(today()) + worstCaseMicros > capMicros) {
                return null;
            }
            return repository.save(new LlmUsageEntity(clock.instant(), today(), model, 0, 0, worstCaseMicros,
                    true, jobKey)).getId();
        });
        return id == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(id);
    }

    /** Replaces a reservation with the real usage. The real figure is stored even if it is above the reservation. */
    public void settle(long reservationId, long inputTokens, long outputTokens, long micros, boolean estimated) {
        tx.executeWithoutResult(status -> {
            lock();
            repository.findById(reservationId).ifPresent(u -> u.settle(inputTokens, outputTokens, micros, estimated));
        });
    }

    /** Gives a reservation back when the call cost nothing. */
    public void release(long reservationId) {
        tx.executeWithoutResult(status -> {
            lock();
            repository.deleteById(reservationId);
        });
    }

    /** Charges one call to today's ledger. */
    public void record(String model, long inputTokens, long outputTokens, long micros, boolean estimated, String jobKey) {
        tx.executeWithoutResult(status -> {
            lock();
            repository.save(new LlmUsageEntity(clock.instant(), today(), model, inputTokens, outputTokens,
                    micros, estimated, jobKey));
        });
    }

    private void lock() {
        jdbc.execute("select pg_advisory_xact_lock(" + LOCK_KEY + ")");
    }
}
