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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "jobs", uniqueConstraints = @UniqueConstraint(columnNames = {"source", "external_id"}))
public class JobEntity {

    public static final int MAX_SOURCE = 64;
    public static final int MAX_EXTERNAL_ID = 255;
    public static final int MAX_TITLE = 512;
    public static final int MAX_TEXT = 512;
    public static final int MAX_URL = 2048;
    public static final int MAX_CATEGORY = 128;
    public static final int MAX_JOB_TYPE = 64;
    public static final int MAX_SALARY = 256;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(nullable = false, length = MAX_SOURCE)
    @Getter
    private String source;

    @Column(name = "external_id", nullable = false, length = MAX_EXTERNAL_ID)
    @Getter
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

    @Column(length = MAX_CATEGORY)
    private String category;

    @Column(name = "job_type", length = MAX_JOB_TYPE)
    private String jobType;

    @Column(length = MAX_SALARY)
    private String salary;

    @Column(name = "source_updated_at")
    @Getter
    private Instant sourceUpdatedAt;

    @Column(name = "first_seen_at", nullable = false)
    @Getter
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    @Getter
    private Instant lastSeenAt;

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
        this.category = p.category();
        this.jobType = p.jobType();
        this.salary = p.salary();
        this.sourceUpdatedAt = p.updatedAt();
        this.lastSeenAt = seenAt;
    }

    public JobRecord toRecord() {
        return new JobRecord(
                new JobPosting(externalId, source, title, company, location, url, description, postedAt,
                        category, jobType, salary, sourceUpdatedAt),
                firstSeenAt);
    }
}
