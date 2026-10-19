package com.gnemirko.jobapplier.scoring.entity;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.dto.Assessment;
import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.dto.Gap;
import com.gnemirko.jobapplier.scoring.dto.InterviewChance;
import com.gnemirko.jobapplier.scoring.dto.Tier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A stored vacancy and the verdict on it. The job payload is kept so a later run can score it
 * without asking ingestion again. Everything the model wrote (reason, gaps) is untrusted text:
 * escape it wherever it is shown.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "assessments", uniqueConstraints = @UniqueConstraint(columnNames = {"source", "external_id"}))
public class AssessmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String company;

    @Column(columnDefinition = "text")
    private String location;

    @Column(nullable = false, columnDefinition = "text")
    private String url;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssessmentStatus status;

    @Column(nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_chance", length = 16)
    private InterviewChance interviewChance;

    /** Model's one-liner when scored; the filter's reason when rejected. */
    @Column(columnDefinition = "text")
    private String reason;

    /** 1-based numbers of the profile's must-haves that the job meets. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "met_must_haves", columnDefinition = "jsonb")
    private List<Integer> metMustHaves;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Gap> gaps;

    @Column(length = 200)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "scored_at")
    private Instant scoredAt;

    public static AssessmentEntity pending(JobRecord record, Instant now) {
        AssessmentEntity e = new AssessmentEntity();
        JobPosting p = record.posting();
        e.source = p.source();
        e.externalId = p.externalId();
        e.title = p.title();
        e.company = p.company();
        e.location = p.location();
        e.url = p.url();
        e.description = p.description();
        e.postedAt = p.postedAt();
        e.firstSeenAt = record.firstSeenAt();
        e.status = AssessmentStatus.PENDING;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    /** The stored payload as a record, for scoring without ingestion. */
    public JobRecord toRecord() {
        return new JobRecord(new JobPosting(externalId, source, title, company, location, url, description,
                postedAt, null, null, null, null), firstSeenAt);
    }

    public String jobKey() {
        return source + ":" + externalId;
    }

    public void reject(String why, Instant now) {
        this.status = AssessmentStatus.REJECTED_FILTER;
        this.reason = why;
        touch(now);
    }

    public void score(Assessment a, String modelName, Instant now) {
        this.status = AssessmentStatus.SCORED;
        this.tier = a.tier();
        this.interviewChance = a.interviewChance();
        this.reason = a.reason();
        this.metMustHaves = a.metMustHaves();
        this.gaps = a.gaps();
        this.model = modelName;
        this.scoredAt = now;
        touch(now);
    }

    /** Counts one failed attempt; the row becomes permanent when the attempts are used up. */
    public void fail(String why, int maxAttempts, Instant now) {
        this.attempts++;
        this.status = attempts >= maxAttempts ? AssessmentStatus.FAILED_PERMANENT : AssessmentStatus.FAILED;
        this.reason = why;
        touch(now);
    }

    /** Budget ran out before this row was tried: no attempt is used. */
    public void leaveUnscoredForBudget(Instant now) {
        this.status = AssessmentStatus.UNSCORED_BUDGET;
        touch(now);
    }

    /** A charged attempt that produced nothing usable, before the row is left for budget. */
    public void countAttempt() {
        this.attempts++;
    }

    public void makePermanent(String why, Instant now) {
        this.status = AssessmentStatus.FAILED_PERMANENT;
        if (why != null) {
            this.reason = why;
        }
        touch(now);
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }
}
