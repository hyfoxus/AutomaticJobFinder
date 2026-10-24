package com.gnemirko.jobapplier.digest.service;

import com.gnemirko.jobapplier.digest.dto.Digest;
import com.gnemirko.jobapplier.digest.exception.DeliveryException;

/** Hands a digest over: by SMTP, or into a file in dry-run mode. */
public interface DigestMailer {

    /** @throws DeliveryException when the digest was not accepted */
    void deliver(Digest digest);
}
