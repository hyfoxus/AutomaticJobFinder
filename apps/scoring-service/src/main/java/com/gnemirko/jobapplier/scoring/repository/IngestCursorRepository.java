package com.gnemirko.jobapplier.scoring.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.gnemirko.jobapplier.scoring.entity.IngestCursorEntity;

public interface IngestCursorRepository extends JpaRepository<IngestCursorEntity, Short> {

    /** Moves the cursor to {@code seenAt} unless it is already later. Creates the row on first use. */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into scoring.ingest_cursor (id, last_first_seen_at, updated_at)
            values (1, :seenAt, :now)
            on conflict (id) do update
              set last_first_seen_at = greatest(scoring.ingest_cursor.last_first_seen_at, excluded.last_first_seen_at),
                  updated_at = excluded.updated_at
            """)
    void advance(@Param("seenAt") Instant seenAt, @Param("now") Instant now);
}
