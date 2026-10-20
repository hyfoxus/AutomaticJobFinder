package com.gnemirko.jobapplier.scoring.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The single cursor row (id 1). It is only ever moved forward, by {@code IngestCursorRepository#advance}. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ingest_cursor")
public class IngestCursorEntity {

    public static final short ID = 1;

    @Id
    private Short id;

    @Column(name = "last_first_seen_at", nullable = false)
    private Instant lastFirstSeenAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
