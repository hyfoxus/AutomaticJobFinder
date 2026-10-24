package com.gnemirko.jobapplier.digest.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A job that has been mailed. Rows are written by {@code SentAssessmentRepository#record}. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "sent_assessment")
public class SentAssessmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_id", nullable = false, unique = true)
    private Long assessmentId;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "digest_id", nullable = false)
    private UUID digestId;
}
