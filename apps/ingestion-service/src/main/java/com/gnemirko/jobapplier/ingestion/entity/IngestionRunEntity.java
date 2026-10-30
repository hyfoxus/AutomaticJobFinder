package com.gnemirko.jobapplier.ingestion.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ingestion_runs")
public class IngestionRunEntity {

    public enum Status { SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(nullable = false, length = 64)
    @Getter
    private String source;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Getter
    private int fetched;
    @Getter
    private int inserted;
    @Getter
    private int updated;
    @Getter
    private int skipped;

    @Column(length = 2000)
    @Getter
    private String error;

    public IngestionRunEntity(String source, Instant startedAt, Instant finishedAt, Status status,
            int fetched, int inserted, int updated, int skipped, String error) {
        this.source = source;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.status = status.name();
        this.fetched = fetched;
        this.inserted = inserted;
        this.updated = updated;
        this.skipped = skipped;
        this.error = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
    }

    /** Health state judged for this run (null before the check ran or for older rows). */
    @Column(length = 16)
    @Getter
    private String health;

    public void markHealth(String state) {
        this.health = state;
    }

    public Status getStatus() {
        return Status.valueOf(status);
    }
}
