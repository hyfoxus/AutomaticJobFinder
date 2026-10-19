package com.gnemirko.jobapplier.scoring.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import com.gnemirko.jobapplier.scoring.util.ProviderErrors.Kind;

class ProviderErrorsTest {

    /** Mimics an SDK exception that carries the HTTP status. */
    static class SdkStatusException extends RuntimeException {
        private final int status;

        SdkStatusException(int status) {
            this.status = status;
        }

        public int statusCode() {
            return status;
        }
    }

    static class UnauthorizedException extends RuntimeException {
    }

    @Test
    void authIsDetectedFromStatusOrName() {
        assertThat(ProviderErrors.classify(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo(Kind.AUTH);
        assertThat(ProviderErrors.classify(new RuntimeException(new SdkStatusException(403)))).isEqualTo(Kind.AUTH);
        assertThat(ProviderErrors.classify(new UnauthorizedException())).isEqualTo(Kind.AUTH);
    }

    @Test
    void timeoutsAreDetectedAnywhereInTheChain() {
        assertThat(ProviderErrors.classify(new RuntimeException(new SocketTimeoutException("x")))).isEqualTo(Kind.TIMEOUT);
        assertThat(ProviderErrors.classify(new RuntimeException(new HttpTimeoutException("x")))).isEqualTo(Kind.TIMEOUT);
    }

    @Test
    void interruptedIoAndTimeoutMessagesAreTimeouts() {
        assertThat(ProviderErrors.classify(new RuntimeException("a", new RuntimeException("b",
                new java.io.InterruptedIOException("timeout"))))).isEqualTo(Kind.TIMEOUT);
        assertThat(ProviderErrors.classify(new RuntimeException("x", new RuntimeException("Request Timed Out upstream"))))
                .isEqualTo(Kind.TIMEOUT);
        assertThat(ProviderErrors.classify(new RuntimeException("x", new RuntimeException("y", new java.io.IOException("read timeout")))))
                .isEqualTo(Kind.TIMEOUT);
    }

    @Test
    void nonTransientClientErrorsAreRejected() {
        for (HttpStatus s : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.TOO_MANY_REQUESTS}) {
            assertThat(ProviderErrors.classify(new RuntimeException(
                    HttpClientErrorException.create(s, "", HttpHeaders.EMPTY, new byte[0], null)))).isEqualTo(Kind.REJECTED);
        }
        assertThat(ProviderErrors.classify(new SdkStatusException(400))).isEqualTo(Kind.REJECTED);
    }

    @Test
    void everythingElseIsOther() {
        assertThat(ProviderErrors.classify(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo(Kind.OTHER);
        assertThat(ProviderErrors.classify(new SdkStatusException(500))).isEqualTo(Kind.OTHER);
    }
}
