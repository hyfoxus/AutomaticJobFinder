package com.gnemirko.jobapplier.ingestion.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gnemirko.jobapplier.ingestion.entity.JobEntity;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    List<JobEntity> findBySourceAndExternalIdIn(String source, Collection<String> externalIds);

    Optional<JobEntity> findBySourceAndExternalId(String source, String externalId);

    @Query("select j from JobEntity j where j.firstSeenAt > :since order by j.firstSeenAt asc, j.id asc")
    List<JobEntity> findFirstSeenAfter(@Param("since") Instant since);
}
