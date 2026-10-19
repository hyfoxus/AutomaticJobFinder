package com.gnemirko.jobapplier.scoring.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.scoring.dto.Profile;

class ProfileLoaderTest {

    @Test
    void sampleProfileAtTheRepoRootIsValid() {
        Profile p = ProfileLoader.load(Path.of("../../profile.sample.yml"));
        assertThat(p.mustHaves()).isNotEmpty();
        assertThat(p.filters().excludeTitleRegex()).isNotEmpty();
    }
}
