package com.gnemirko.jobapplier.scoring.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
