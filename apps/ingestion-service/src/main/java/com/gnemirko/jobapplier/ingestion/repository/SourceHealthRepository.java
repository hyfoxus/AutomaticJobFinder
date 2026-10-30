package com.gnemirko.jobapplier.ingestion.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gnemirko.jobapplier.ingestion.entity.SourceHealthEntity;

public interface SourceHealthRepository extends JpaRepository<SourceHealthEntity, String> {
}
