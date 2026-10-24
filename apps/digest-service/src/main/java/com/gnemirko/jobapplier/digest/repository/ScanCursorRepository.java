package com.gnemirko.jobapplier.digest.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gnemirko.jobapplier.digest.entity.ScanCursorEntity;

public interface ScanCursorRepository extends JpaRepository<ScanCursorEntity, Short> {

    /** Sets the cursor, creating the row on first use. Call inside a transaction. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into digest.scan_cursor (id, scored_at, after_id)
            values (1, :scoredAt, :afterId)
            on conflict (id) do update set scored_at = excluded.scored_at, after_id = excluded.after_id
            """)
    void move(@Param("scoredAt") Instant scoredAt, @Param("afterId") long afterId);
}
