package com.gnemirko.jobapplier.ingestion.entity;

import java.time.Instant;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.contracts.JobRecord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "jobs", uniqueConstraints = @UniqueConstraint(columnNames = {"source", "external_id"}))
public class JobEntity {

    public static final int MAX_SOURCE = 64;
    public static final int MAX_EXTERNAL_ID = 255;
    public static final int MAX_TITLE = 512;
    public static final int MAX_TEXT = 512;
    public static final int MAX_URL = 2048;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = MAX_SOURCE)
    private String source;

    @Column(name = "external_id", nullable = false, length = MAX_EXTERNAL_ID)
    private String externalId;

    @Column(nullable = false, length = MAX_TITLE)
    private String title;

    @Column(length = MAX_TEXT)
    private String company;

    @Column(length = MAX_TEXT)
    private String location;

    @Column(nullable = false, length = MAX_URL)
    private String url;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected JobEntity() {
    }

    public JobEntity(JobPosting p, Instant seenAt) {
        this.source = p.source();
        this.externalId = p.externalId();
        this.firstSeenAt = seenAt;
        refresh(p, seenAt);
    }

    /** Applies the latest data from the source and bumps lastSeenAt. */
    public void refresh(JobPosting p, Instant seenAt) {
        this.title = p.title();
        this.company = p.company();
        this.location = p.location();
        this.url = p.url();
        this.description = p.description();
        this.postedAt = p.postedAt();
        this.lastSeenAt = seenAt;
    }

    public JobRecord toRecord() {
        return new JobRecord(
                new JobPosting(externalId, source, title, company, location, url, description, postedAt),
                firstSeenAt);
    }

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getExternalId() {
        return externalId;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
