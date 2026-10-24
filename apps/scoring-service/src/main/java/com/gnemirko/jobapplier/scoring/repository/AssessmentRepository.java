package com.gnemirko.jobapplier.scoring.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.entity.AssessmentEntity;

public interface AssessmentRepository extends JpaRepository<AssessmentEntity, Long> {

    Optional<AssessmentEntity> findBySourceAndExternalId(String source, String externalId);

    /** Rows that may still be scored, newest first. */
    List<AssessmentEntity> findByStatusInOrderByFirstSeenAtDescIdDesc(Collection<AssessmentStatus> statuses);

    /** One page of rows with this status; the order comes from the pageable. */
    Slice<AssessmentEntity> findByStatus(AssessmentStatus status, Pageable pageable);

    /** One page of all rows; the order comes from the pageable. */
    Slice<AssessmentEntity> findAllBy(Pageable pageable);

    /**
     * SCORED rows after the position (since, afterId) in {@code scoredAt asc, id asc} order: later instants, or the
     * same instant with a larger id. The order comes from the query, not the pageable.
     */
    @Query("""
            select a from AssessmentEntity a
            where a.status = com.gnemirko.jobapplier.scoring.dto.AssessmentStatus.SCORED
              and (a.scoredAt > :since or (a.scoredAt = :since and a.id > :afterId))
            order by a.scoredAt asc, a.id asc
            """)
    List<AssessmentEntity> findScoredAfter(@Param("since") Instant since, @Param("afterId") long afterId,
                                           Pageable pageable);
}
