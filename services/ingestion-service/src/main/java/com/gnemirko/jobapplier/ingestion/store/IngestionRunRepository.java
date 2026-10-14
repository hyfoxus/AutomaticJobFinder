package com.gnemirko.jobapplier.ingestion.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRunRepository extends JpaRepository<IngestionRunEntity, Long> {

    List<IngestionRunEntity> findBySourceOrderByIdAsc(String source);
}
