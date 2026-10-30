package com.gnemirko.jobapplier.ingestion.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "source_health")
@Getter
public class SourceHealthEntity {

    public static final int MAX_NOTE = 300;

    @Id
    @Column(length = 64)
    private String source;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(nullable = false)
    private Instant since;

    @Column(name = "last_seen", nullable = false)
    private int lastSeen;

    private Integer baseline;

    @Column(length = MAX_NOTE)
    private String note;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SourceHealthEntity(String source, String state, Instant now, int lastSeen, Integer baseline, String note) {
        this.source = source;
        this.since = now;
        apply(state, now, lastSeen, baseline, note);
    }

    /** Keeps {@code since} unless the state changes. */
    public void apply(String newState, Instant now, int newLastSeen, Integer newBaseline, String newNote) {
        if (this.state != null && !this.state.equals(newState)) {
            this.since = now;
        }
        this.state = newState;
        this.lastSeen = newLastSeen;
        this.baseline = newBaseline;
        this.note = newNote == null ? null : newNote.substring(0, Math.min(newNote.length(), MAX_NOTE));
        this.updatedAt = now;
    }
}
