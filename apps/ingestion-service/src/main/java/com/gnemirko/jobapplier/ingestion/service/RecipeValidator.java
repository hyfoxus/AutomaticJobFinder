package com.gnemirko.jobapplier.ingestion.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.source.JobSource;

/**
 * The registry's recipe checks as a public service, plus the name rules the registry applies while loading: a name may
 * not be a built-in source or an already loaded recipe.
 */
@Service
@LearnerEnabled
public class RecipeValidator {

    private final Set<String> taken = new HashSet<>();

    @Autowired
    public RecipeValidator(RecipeRegistry registry, List<JobSource> builtIns) {
        builtIns.forEach(s -> taken.add(s.name().toLowerCase(Locale.ROOT)));
        registry.recipes().forEach(r -> taken.add(r.name().toLowerCase(Locale.ROOT)));
    }

    public RecipeValidator(Collection<String> takenNames) {
        takenNames.forEach(n -> taken.add(n.toLowerCase(Locale.ROOT)));
    }

    /** True when a recipe of this name is loaded or a built-in source has it. */
    public boolean isTaken(String name) {
        return taken.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Throws IllegalArgumentException describing the first problem found. */
    public void validate(Recipe recipe) {
        RecipeRegistry.validate(recipe);
        if (taken.contains(recipe.name().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("duplicate or reserved name '" + recipe.name() + "'");
        }
    }
}
