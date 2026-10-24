package com.gnemirko.jobapplier.digest.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gnemirko.jobapplier.digest.entity.SentAssessmentEntity;

public interface SentAssessmentRepository extends JpaRepository<SentAssessmentEntity, Long> {

    /** Which of these assessment ids were already mailed. */
    @Query("select s.assessmentId from SentAssessmentEntity s where s.assessmentId in :ids")
    Set<Long> findSentIds(@Param("ids") Collection<Long> ids);

    /** Records one job as mailed; a second record of the same job is ignored. Call inside a transaction. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into digest.sent_assessment (assessment_id, sent_at, digest_id)
            values (:assessmentId, :sentAt, :digestId)
            on conflict (assessment_id) do nothing
            """)
    int record(@Param("assessmentId") long assessmentId, @Param("sentAt") Instant sentAt,
               @Param("digestId") UUID digestId);
}
