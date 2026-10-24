package com.gnemirko.jobapplier.digest.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The single cursor row (id 1): the scan continues after (scoredAt, afterId). Written by {@code ScanCursorRepository#save}. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "scan_cursor")
public class ScanCursorEntity {

    public static final short ID = 1;

    @Id
    private Short id;

    @Column(name = "scored_at", nullable = false)
    private Instant scoredAt;

    @Column(name = "after_id", nullable = false)
    private long afterId;
}
