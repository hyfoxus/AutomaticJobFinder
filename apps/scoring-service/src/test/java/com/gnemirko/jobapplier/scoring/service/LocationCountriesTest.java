package com.gnemirko.jobapplier.scoring.service;

import static com.gnemirko.jobapplier.scoring.service.UnitSupport.posting;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mock.env.MockEnvironment;

import com.gnemirko.jobapplier.scoring.FakeChatModel;
import com.gnemirko.jobapplier.scoring.dto.Profile;
import com.gnemirko.jobapplier.scoring.util.ProfileLoader;

/** The profile's locationCountries is for ingestion only: it must bind, and scoring must ignore it. */
class LocationCountriesTest {

    @TempDir Path tmp;

    private Profile profile() throws Exception {
        Path f = tmp.resolve("profile.yml");
        Files.writeString(f, "skillsSummary: s\nmustHaves: [a]\nlocationCountries: [QQMARK, europe]\n"
                + "filters:\n  allowedLocationKeywords: [remote]\n");
        return ProfileLoader.load(f);
    }

    @Test
    void bindsWithoutStrictBindingFailing() throws Exception {
        assertThat(profile().countries()).containsExactly("QQMARK", "europe");
        assertThat(ProfileLoader.load(Path.of("../../profile.sample.yml")).countries()).isNotNull();
    }

    @Test
    void hardFilterIgnoresIt() throws Exception {
        assertThat(new HardFilter(profile()).reject(posting("Dev", "Acme", "QQMARK", "d"))).isPresent();
        assertThat(new HardFilter(profile()).reject(posting("Dev", "Acme", "Remote", "d"))).isEmpty();
    }

    @Test
    void promptNeverPrintsIt() throws Exception {
        Profile p = profile();
        var props = UnitSupport.props(1, 1, 500, "1");
        var builder = new PromptBuilder(p, props, new FitAssessor(ChatClient.create(new FakeChatModel()), p),
                new ChatOptionsFactory(props, new MockEnvironment()));
        var prompt = builder.build(posting("Dev", "Acme", "Remote", "d")).prompt();
        prompt.getInstructions().forEach(m -> assertThat(m.getText()).doesNotContain("QQMARK").doesNotContain("europe"));
    }

    private Profile with(String value) throws Exception {
        Path f = tmp.resolve("p2.yml");
        Files.writeString(f, "skillsSummary: s\nmustHaves: [a]\nlocationCountries: " + value + "\n");
        return ProfileLoader.load(f);
    }

    @Test
    void toleratesNullItemScalarAndMapping() throws Exception {
        assertThat(with("[ie, null, de]").countries()).containsExactly("ie", "de");
        assertThat(with("ie").countries()).isEmpty();
        assertThat(with("{a: b}").countries()).isEmpty();
    }
}
