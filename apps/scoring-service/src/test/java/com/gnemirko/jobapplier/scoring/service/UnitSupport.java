package com.gnemirko.jobapplier.scoring.service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;
import com.gnemirko.jobapplier.scoring.dto.Profile;
import com.gnemirko.jobapplier.scoring.util.ProfileLoader;

final class UnitSupport {

    private UnitSupport() {
    }

    static Profile profile() {
        return ProfileLoader.load(Path.of("src/test/resources/profile.test.yml"));
    }

    static ScoringProperties props(double inPrice, double outPrice, int maxTokens, String capUsd) {
        return new ScoringProperties(true, "x", 3,
                new ScoringProperties.Llm("m", false, maxTokens, 6000,
                        Map.of("m", new ScoringProperties.Price(BigDecimal.valueOf(inPrice), BigDecimal.valueOf(outPrice))), null),
                new ScoringProperties.Budget(new BigDecimal(capUsd), "UTC"));
    }

    static JobPosting posting(String title, String company, String location, String description) {
        return new JobPosting("1", "test", title, company, location, "https://example.com/1", description,
                null, null, null, null, null);
    }
}
