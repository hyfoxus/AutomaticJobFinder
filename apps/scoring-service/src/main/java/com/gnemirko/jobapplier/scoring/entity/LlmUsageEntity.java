package com.gnemirko.jobapplier.scoring.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One model call and what it cost, in micro-dollars. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "llm_usage")
public class LlmUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant ts;

    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false, length = 200)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private long micros;

    @Column(nullable = false)
    private boolean estimated;

    @Column(name = "job_key", nullable = false, length = 400)
    private String jobKey;

    /** Replaces a reservation with what the call really used. */
    public void settle(long inputTokens, long outputTokens, long micros, boolean estimated) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.micros = micros;
        this.estimated = estimated;
    }

    public LlmUsageEntity(Instant ts, LocalDate day, String model, long inputTokens, long outputTokens,
                          long micros, boolean estimated, String jobKey) {
        this.ts = ts;
        this.day = day;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.micros = micros;
        this.estimated = estimated;
        this.jobKey = jobKey.length() > 400 ? jobKey.substring(0, 400) : jobKey;
    }
}
