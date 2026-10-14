package com.gnemirko.jobapplier.ingestion.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ingestion_runs")
public class IngestionRunEntity {

    public enum Status { SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(nullable = false, length = 16)
    private String status;

    private int fetched;
    private int inserted;
    private int updated;
    private int skipped;

    @Column(length = 2000)
    private String error;

    protected IngestionRunEntity() {
    }

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

    public String getSource() {
        return source;
    }

    public Status getStatus() {
        return Status.valueOf(status);
    }

    public int getFetched() {
        return fetched;
    }

    public int getInserted() {
        return inserted;
    }

    public int getUpdated() {
        return updated;
    }

    public int getSkipped() {
        return skipped;
    }

    public String getError() {
        return error;
    }
}
