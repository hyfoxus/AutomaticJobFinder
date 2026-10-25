package com.gnemirko.jobapplier.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** A real servlet container: an unsatisfiable Range makes the resource handler call sendError(416), a real error dispatch to /error; 416 is not on the whitelist, so it shows as 500. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ui.enabled=true")
class ErrorDispatchTest {

    @Value("${local.server.port}") int port;

    private String raw(String request) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.getInputStream().read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void containerErrorDispatchCarriesTheHeadersAndFixedText() throws Exception {
        String res = raw("GET /ui.css HTTP/1.1\r\nHost: localhost\r\nRange: bytes=99999-999999\r\nConnection: close\r\n\r\n");
        assertThat(res).startsWith("HTTP/1.1 500");
        assertThat(res.toLowerCase()).contains("content-security-policy: default-src 'none'")
                .contains("x-content-type-options: nosniff").contains("cache-control: no-store")
                .contains("x-frame-options: deny").contains("referrer-policy: no-referrer");
        assertThat(res).contains("Something went wrong.").doesNotContain("Exception");
    }
}
