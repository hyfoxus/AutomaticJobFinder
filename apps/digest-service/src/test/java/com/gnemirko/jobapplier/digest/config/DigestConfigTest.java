package com.gnemirko.jobapplier.digest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.gnemirko.jobapplier.digest.exception.DigestConfigurationException;
import com.gnemirko.jobapplier.digest.service.DigestMailer;
import com.gnemirko.jobapplier.digest.service.DryRunMailer;
import com.gnemirko.jobapplier.digest.service.SmtpMailer;

class DigestConfigTest {

    @Configuration
    @EnableConfigurationProperties(DigestProperties.class)
    @Import(DigestConfig.class)
    static class Base {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private ApplicationContextRunner runner(String... props) {
        String[] all = new String[props.length + 1];
        all[0] = "digest.enabled=true";
        System.arraycopy(props, 0, all, 1, props.length);
        return new ApplicationContextRunner().withUserConfiguration(Base.class).withPropertyValues(all);
    }

    private static final String[] SMTP = {"digest.mode=smtp", "digest.smtp.host=127.0.0.1",
            "digest.smtp.from=jobs@example.com", "digest.smtp.to=me@example.com"};

    private ApplicationContextRunner smtp(String... extra) {
        String[] all = new String[SMTP.length + extra.length];
        System.arraycopy(SMTP, 0, all, 0, SMTP.length);
        System.arraycopy(extra, 0, all, SMTP.length, extra.length);
        return runner(all);
    }

    @Test
    void defaultsStartInDryRun() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(DigestMailer.class)).isInstanceOf(DryRunMailer.class);
        });
    }

    @Test
    void validSmtpSettingsStart() {
        smtp().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(DigestMailer.class)).isInstanceOf(SmtpMailer.class);
        });
        smtp("digest.smtp.to=a@example.com,b@example.com").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    private static Throwable root(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static void fails(ApplicationContextRunner r, String message) {
        r.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(root(ctx.getStartupFailure())).isInstanceOf(DigestConfigurationException.class)
                    .hasMessageContaining(message);
        });
    }

    @Test
    void smtpModeNeedsHostFromAndTo() {
        fails(runner("digest.mode=smtp", "digest.smtp.from=a@b.co", "digest.smtp.to=a@b.co"), "digest.smtp.host");
        fails(runner("digest.mode=smtp", "digest.smtp.host=h.example", "digest.smtp.to=a@b.co"), "digest.smtp.from");
        fails(runner("digest.mode=smtp", "digest.smtp.host=h.example", "digest.smtp.from=a@b.co"), "digest.smtp.to");
    }

    @Test
    void badAddressesAreRefused() {
        fails(smtp("digest.smtp.to=me@example.com\r\nBcc: x@evil.example"), "not a valid e-mail address");
        fails(smtp("digest.smtp.to=not-an-address"), "not a valid e-mail address");
        fails(smtp("digest.smtp.to=a@example.com,bad"), "not a valid e-mail address");
        fails(smtp("digest.smtp.from=jobs@example.com\nX: y"), "not a valid e-mail address");
    }

    @Test
    void nonPositiveOverlapAndOtherBadValuesAreRefused() {
        fails(runner("digest.overlap=0s"), "digest.overlap must be greater than 0");
        fails(runner("digest.overlap=-5m"), "digest.overlap must be greater than 0");
        fails(runner("digest.max-items=0"), "digest.max-items");
        fails(runner("digest.scoring.base-url=ftp://x"), "digest.scoring.base-url");
        fails(runner("digest.initial-lookback=0s"), "digest.initial-lookback");
    }

    @Test
    void unknownModeIsRefused() {
        runner("digest.mode=bogus").run(ctx -> {
            assertThat(ctx).hasFailed();
            Throwable t = ctx.getStartupFailure();
            StringBuilder all = new StringBuilder();
            for (; t != null; t = t.getCause()) {
                all.append(t.getMessage()).append('\n');
            }
            assertThat(all.toString()).contains("bogus");
        });
    }

    @Test
    void disabledCreatesNothing() {
        new ApplicationContextRunner().withUserConfiguration(Base.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(DigestMailer.class);
        });
    }

    private static DigestProperties.Smtp smtpSettings(String host, boolean ssl, boolean plaintext) {
        return new DigestProperties.Smtp(host, 587, "user", "a@b.co", java.util.List.of("c@d.co"), ssl, plaintext);
    }

    @Test
    void remoteHostRequiresStartTlsAndChecksTheServerName() {
        Properties p = DigestConfig.mailProperties(smtpSettings("smtp.example.com", false, false));

        assertThat(p).containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.debug", "false");
    }

    @Test
    void implicitTlsIsSupportedAndLoopbackMayBePlain() {
        Properties ssl = DigestConfig.mailProperties(smtpSettings("smtp.example.com", true, false));
        assertThat(ssl).containsEntry("mail.smtps.ssl.enable", "true")
                .containsEntry("mail.smtps.ssl.checkserveridentity", "true");

        for (String host : new String[] {"localhost", "127.0.0.1", "::1"}) {
            assertThat(DigestConfig.mailProperties(smtpSettings(host, false, false)))
                    .doesNotContainKey("mail.smtp.starttls.required");
        }
        assertThat(DigestConfig.mailProperties(smtpSettings("smtp.example.com", false, true)))
                .doesNotContainKey("mail.smtp.starttls.required");
    }

}
