package com.gnemirko.jobapplier.ingestion.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gnemirko.jobapplier.ingestion.entity.JobEntity;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    List<JobEntity> findBySourceAndExternalIdIn(String source, Collection<String> externalIds);

    Optional<JobEntity> findBySourceAndExternalId(String source, String externalId);

    @Query("select j from JobEntity j where j.firstSeenAt > :since order by j.firstSeenAt asc, j.id asc")
    List<JobEntity> findFirstSeenAfter(@Param("since") Instant since);

    /** Rows of (url, sourceUpdatedAt) for a source; the marker may be null. */
    @Query("select j.url, j.sourceUpdatedAt from JobEntity j where j.source = :source")
    List<Object[]> findUrlMarkersBySource(@Param("source") String source);

    /** Bumps lastSeenAt for stored jobs of a source by canonical url; returns rows changed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update JobEntity j set j.lastSeenAt = :seenAt where j.source = :source and j.url in :urls")
    int touchLastSeen(@Param("source") String source, @Param("urls") Collection<String> urls,
            @Param("seenAt") Instant seenAt);
}
