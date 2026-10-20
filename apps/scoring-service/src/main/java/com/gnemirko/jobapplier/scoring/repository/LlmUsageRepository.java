package com.gnemirko.jobapplier.scoring.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gnemirko.jobapplier.scoring.entity.LlmUsageEntity;

public interface LlmUsageRepository extends JpaRepository<LlmUsageEntity, Long> {

    @Query("select coalesce(sum(u.micros), 0) from LlmUsageEntity u where u.day = :day")
    long sumMicrosForDay(@Param("day") LocalDate day);

    long countByDay(LocalDate day);
}
