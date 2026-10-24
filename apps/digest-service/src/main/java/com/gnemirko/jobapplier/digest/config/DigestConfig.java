package com.gnemirko.jobapplier.digest.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.gnemirko.jobapplier.digest.exception.DigestConfigurationException;
import com.gnemirko.jobapplier.digest.service.DigestMailer;
import com.gnemirko.jobapplier.digest.service.DryRunMailer;
import com.gnemirko.jobapplier.digest.service.SmtpMailer;
import com.gnemirko.jobapplier.digest.util.Addresses;

import lombok.extern.slf4j.Slf4j;

/** Beans that exist only when the digest is enabled. Every misconfiguration stops startup with a clear message. */
@Slf4j
@Configuration
@DigestEnabled
@EnableScheduling
public class DigestConfig {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");

    @Bean
    DigestSettingsCheck digestSettingsCheck(DigestProperties props) {
        check(props);
        return new DigestSettingsCheck();
    }

    @Bean
    DigestMailer digestMailer(DigestProperties props, Environment env, Clock clock, DigestSettingsCheck check) {
        if (props.mode() == DigestProperties.Mode.SMTP) {
            DigestProperties.Smtp smtp = props.smtp();
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(smtp.host().trim());
            sender.setPort(smtp.port());
            sender.setProtocol(smtp.ssl() ? "smtps" : "smtp");
            sender.setDefaultEncoding("UTF-8");
            String user = smtp.username();
            String password = env.getProperty("digest.smtp.password", "");
            if (user != null && !user.isBlank()) {
                sender.setUsername(user.trim());
                sender.setPassword(password);
            }
            sender.setJavaMailProperties(mailProperties(smtp));
            if (!isLoopback(smtp.host()) && smtp.allowPlaintext() && !smtp.ssl()) {
                log.warn("digest.smtp.allow-plaintext=true: mail to {} may go over an unencrypted connection", smtp.host());
            }
            return new SmtpMailer(sender, smtp.from().trim(), Addresses.parseAll(smtp.to()));
        }
        return new DryRunMailer(Path.of(props.outDir()), clock);
    }

    /** The JavaMail properties for the SMTP settings: TLS required unless the host is loopback (or opted out). */
    public static Properties mailProperties(DigestProperties.Smtp smtp) {
        String p = smtp.ssl() ? "mail.smtps." : "mail.smtp.";
        Properties out = new Properties();
        out.put("mail.debug", "false");
        out.put(p + "connectiontimeout", "10000");
        out.put(p + "timeout", "30000");
        out.put(p + "writetimeout", "30000");
        boolean plainOk = isLoopback(smtp.host()) || smtp.allowPlaintext();
        if (smtp.ssl()) {
            out.put(p + "ssl.enable", "true");
            out.put(p + "ssl.checkserveridentity", "true");
        } else if (plainOk) {
            out.put(p + "starttls.enable", "true");
        } else {
            out.put(p + "starttls.enable", "true");
            out.put(p + "starttls.required", "true");
            out.put(p + "ssl.checkserveridentity", "true");
        }
        if (smtp.username() != null && !smtp.username().isBlank()) {
            out.put(p + "auth", "true");
        }
        return out;
    }

    static boolean isLoopback(String host) {
        return host != null && LOOPBACK.contains(host.trim().toLowerCase(Locale.ROOT));
    }

    static void check(DigestProperties props) {
        if (props.mode() == null) {
            throw new DigestConfigurationException("digest.mode must be dry-run or smtp");
        }
        if (props.overlap() == null || props.overlap().isZero() || props.overlap().isNegative()) {
            throw new DigestConfigurationException("digest.overlap must be greater than 0");
        }
        if (props.initialLookback() == null || props.initialLookback().isZero() || props.initialLookback().isNegative()) {
            throw new DigestConfigurationException("digest.initial-lookback must be greater than 0");
        }
        if (props.maxItems() < 1) {
            throw new DigestConfigurationException("digest.max-items must be at least 1");
        }
        if (props.tiers() == null || props.tiers().isEmpty()) {
            throw new DigestConfigurationException("digest.tiers must name at least one tier");
        }
        if (props.outDir() == null || props.outDir().isBlank()) {
            throw new DigestConfigurationException("digest.out-dir must be set");
        }
        DigestProperties.Scoring s = props.scoring();
        try {
            URI uri = URI.create(s.baseUrl().trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new DigestConfigurationException("digest.scoring.base-url must be an http(s) URL");
        }
        if (s.connectTimeout() == null || s.connectTimeout().isZero() || s.connectTimeout().isNegative()
                || s.readTimeout() == null || s.readTimeout().isZero() || s.readTimeout().isNegative()) {
            throw new DigestConfigurationException("digest.scoring timeouts must be greater than 0");
        }
        if (s.maxBytes() < 1) {
            throw new DigestConfigurationException("digest.scoring.max-bytes must be at least 1");
        }
        if (props.mode() == DigestProperties.Mode.SMTP) {
            DigestProperties.Smtp smtp = props.smtp();
            if (smtp.host() == null || smtp.host().isBlank()) {
                throw new DigestConfigurationException("digest.mode=smtp needs digest.smtp.host");
            }
            if (smtp.port() < 1 || smtp.port() > 65535) {
                throw new DigestConfigurationException("digest.smtp.port must be 1..65535");
            }
            if (smtp.from() == null || smtp.from().isBlank()) {
                throw new DigestConfigurationException("digest.mode=smtp needs digest.smtp.from");
            }
            List<String> to = smtp.to() == null ? List.of() : smtp.to();
            if (to.stream().noneMatch(a -> a != null && !a.isBlank())) {
                throw new DigestConfigurationException("digest.mode=smtp needs digest.smtp.to");
            }
            Addresses.parse(smtp.from());
            Addresses.parseAll(to);
        }
    }

    /** Marker so beans can depend on the settings having been checked first. */
    public static final class DigestSettingsCheck {
    }
}
