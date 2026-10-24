package com.gnemirko.jobapplier.digest.util;

import java.util.List;

import com.gnemirko.jobapplier.digest.exception.DigestConfigurationException;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/** Strict e-mail address checks for the configured sender and recipients. */
public final class Addresses {

    private Addresses() {
    }

    /** A plain {@code user@host} address: no CR/LF or other control characters, no display name, strictly parsed. */
    public static InternetAddress parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DigestConfigurationException("an e-mail address is empty");
        }
        String a = raw.trim();
        if (a.chars().anyMatch(c -> c < 0x20 || c == 0x7f || Character.isWhitespace(c) || c == '<' || c == '>' || c == ',')) {
            throw new DigestConfigurationException("not a valid e-mail address: " + printable(a));
        }
        try {
            InternetAddress address = new InternetAddress(a, true);
            address.validate();
            if (address.getAddress().indexOf('@') < 1) {
                throw new AddressException("no domain");
            }
            return address;
        } catch (AddressException e) {
            throw new DigestConfigurationException("not a valid e-mail address: " + printable(a));
        }
    }

    public static InternetAddress[] parseAll(List<String> raw) {
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(Addresses::parse)
                .toArray(InternetAddress[]::new);
    }

    private static String printable(String s) {
        return s.replaceAll("\\p{Cntrl}", "?");
    }
}
