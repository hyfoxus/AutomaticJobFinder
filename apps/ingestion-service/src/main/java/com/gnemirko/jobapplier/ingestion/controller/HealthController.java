package com.gnemirko.jobapplier.ingestion.controller;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gnemirko.jobapplier.ingestion.dto.SourceHealthView;
import com.gnemirko.jobapplier.ingestion.entity.SourceHealthEntity;
import com.gnemirko.jobapplier.ingestion.repository.SourceHealthRepository;
import com.gnemirko.jobapplier.ingestion.service.SourceRegistry;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import lombok.RequiredArgsConstructor;

/** Read-only view of the recipe health states. Rows of sources that are no longer registered show as INACTIVE. */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final SourceHealthRepository healths;
    private final SourceRegistry sources;

    @GetMapping("/sources")
    public List<SourceHealthView> sources() {
        Set<String> active = sources.all().stream().map(JobSource::name).collect(Collectors.toSet());
        return healths.findAll().stream()
                .sorted(java.util.Comparator.comparing(SourceHealthEntity::getSource))
                .map(h -> new SourceHealthView(h.getSource(), active.contains(h.getSource()) ? h.getState() : "INACTIVE",
                        h.getSince(), h.getLastSeen(), h.getBaseline(), h.getNote(), h.getUpdatedAt()))
                .toList();
    }
}
