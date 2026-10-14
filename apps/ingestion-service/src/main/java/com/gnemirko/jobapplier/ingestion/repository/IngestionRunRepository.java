package com.gnemirko.jobapplier.ingestion.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;

public interface IngestionRunRepository extends JpaRepository<IngestionRunEntity, Long> {

    List<IngestionRunEntity> findBySourceOrderByIdAsc(String source);
}
