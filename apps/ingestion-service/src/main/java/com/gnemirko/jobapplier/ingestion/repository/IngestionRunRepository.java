package com.gnemirko.jobapplier.ingestion.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;

public interface IngestionRunRepository extends JpaRepository<IngestionRunEntity, Long> {

    List<IngestionRunEntity> findBySourceOrderByIdAsc(String source);

    /** Earlier successful runs of a source that were not judged unhealthy, newest first (health check baseline). Empty and partial runs are excluded; the markers mirror RunNotes.. */
    @Query("""
            select r from IngestionRunEntity r
            where r.source = :source and r.id < :beforeId and r.status = 'SUCCESS'
              and r.finishedAt > :cutoff and (r.health is null or r.health = 'HEALTHY')
              and r.inserted + r.updated > 0
              and (r.error is null or (r.error not like '%Stopped early: run time budget%'
                   and r.error not like '%disallowed by robots.txt%'))
            order by r.id desc
            """)
    List<IngestionRunEntity> baselineCandidates(@Param("source") String source, @Param("beforeId") long beforeId,
            @Param("cutoff") Instant cutoff, Pageable page);
}
