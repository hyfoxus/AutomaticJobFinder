package com.gnemirko.jobapplier.ingestion.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.RecipeJobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.SitemapReader;
import com.gnemirko.jobapplier.ingestion.source.recipe.SitemapRecipeJobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.WorkdayRecipeJobSource;

/** Every source the runner executes: built-ins first (Remotive), then one per enabled recipe. */
@Slf4j
@Service
public class SourceRegistry {

    private final List<JobSource> sources;

    public SourceRegistry(List<JobSource> builtIns, RecipeRegistry recipes, PoliteFetcher fetcher, Clock clock,
            Duration maxRunDuration) {
        this(builtIns, recipes, fetcher, clock, maxRunDuration, null);
    }

    @Autowired
    public SourceRegistry(List<JobSource> builtIns, RecipeRegistry recipes, PoliteFetcher fetcher, Clock clock,
            @Value("${ingestion.fetch.max-run-duration:20m}") Duration maxRunDuration,
            @Value("${ingestion.cron:0 0 */6 * * *}") String cron) {
        List<JobSource> all = new ArrayList<>(builtIns);
        for (Recipe recipe : recipes.recipes()) {
            if (recipe.isEnabled()) {
                String strategy = recipe.strategy();
                if (Recipe.STRATEGY_SITEMAP.equals(strategy)) {
                    all.add(new SitemapRecipeJobSource(recipe, fetcher, new SitemapReader(fetcher), clock,
                            maxRunDuration));
                } else if (Recipe.STRATEGY_PAGINATED_LISTING.equals(strategy)) {
                    all.add(new RecipeJobSource(recipe, fetcher, clock, maxRunDuration));
                } else if (Recipe.STRATEGY_WORKDAY.equals(strategy)) {
                    all.add(new WorkdayRecipeJobSource(recipe, fetcher, clock, maxRunDuration));
                } else {
                    throw new IllegalStateException("Unknown recipe strategy '" + strategy + "' in " + recipe.name());
                }
            }
        }
        this.sources = List.copyOf(all);
        warnIfTooSlow(all.size() - builtIns.size(), maxRunDuration, cron);
    }

    private static void warnIfTooSlow(int recipeSources, Duration maxRun, String cron) {
        if (cron == null || recipeSources < 1 || maxRun == null) {
            return;
        }
        try {
            CronExpression expr = CronExpression.parse(cron);
            LocalDateTime first = expr.next(LocalDateTime.now());
            LocalDateTime second = first == null ? null : expr.next(first);
            if (second != null) {
                Duration interval = Duration.between(first, second);
                if (maxRun.multipliedBy(recipeSources).compareTo(interval) > 0) {
                    log.warn("{} enabled recipe sources x a {} run limit can exceed the {} cron interval; some runs"
                            + " may overlap or be skipped", recipeSources, maxRun, interval);
                }
            }
        } catch (RuntimeException e) {
            // an unusual cron value: no warning
        }
    }

    public List<JobSource> all() {
        return sources;
    }
}
