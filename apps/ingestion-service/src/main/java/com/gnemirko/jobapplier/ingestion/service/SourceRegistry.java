package com.gnemirko.jobapplier.ingestion.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.RecipeJobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.SitemapReader;
import com.gnemirko.jobapplier.ingestion.source.recipe.SitemapRecipeJobSource;

/** Every source the runner executes: built-ins first (Remotive), then one per enabled recipe. */
@Service
public class SourceRegistry {

    private final List<JobSource> sources;

    public SourceRegistry(List<JobSource> builtIns, RecipeRegistry recipes, PoliteFetcher fetcher, Clock clock,
            @Value("${ingestion.fetch.max-run-duration:20m}") Duration maxRunDuration) {
        List<JobSource> all = new ArrayList<>(builtIns);
        for (Recipe recipe : recipes.recipes()) {
            if (recipe.isEnabled()) {
                if (Recipe.STRATEGY_SITEMAP.equals(recipe.strategy())) {
                    all.add(new SitemapRecipeJobSource(recipe, fetcher, new SitemapReader(fetcher), clock,
                            maxRunDuration));
                } else {
                    all.add(new RecipeJobSource(recipe, fetcher, clock, maxRunDuration));
                }
            }
        }
        this.sources = List.copyOf(all);
    }

    public List<JobSource> all() {
        return sources;
    }
}
