package com.gnemirko.jobapplier.digest.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.gnemirko.jobapplier.digest.dto.Digest;
import com.gnemirko.jobapplier.digest.exception.DeliveryException;

import lombok.extern.slf4j.Slf4j;

/** Writes {@code digest-<UTC timestamp>.html} and {@code .txt} into the output directory; never overwrites. No SMTP. */
@Slf4j
public class DryRunMailer implements DigestMailer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final Path dir;
    private final Clock clock;

    public DryRunMailer(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    @Override
    public void deliver(Digest digest) {
        try {
            Files.createDirectories(dir);
            String stamp = STAMP.format(clock.instant());
            for (int n = 0; ; n++) {
                String base = "digest-" + stamp + (n == 0 ? "" : "-" + n);
                Path html = dir.resolve(base + ".html");
                Path text = dir.resolve(base + ".txt");
                try {
                    Files.writeString(html, digest.html(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException e) {
                    continue;
                }
                try {
                    Files.writeString(text, digest.text(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException e) {
                    Files.deleteIfExists(html);
                    continue;
                }
                log.info("Dry run: wrote {} jobs to {} (nothing was sent or recorded)", digest.jobs().size(), html);
                return;
            }
        } catch (IOException e) {
            throw new DeliveryException("dry-run file could not be written: " + e.getClass().getSimpleName(), e);
        }
    }
}
