package com.gnemirko.jobapplier.digest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import com.gnemirko.jobapplier.digest.config.DigestProperties;
import com.gnemirko.jobapplier.digest.dto.RunResult.Outcome;

/** The SMTP password never shows up in logs or in the settings record, even when authentication fails. */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "digest.smtp.username=mailuser",
        "digest.smtp.password=" + DigestPasswordTest.PASSWORD
})
class DigestPasswordTest extends DigestFlowTestBase {

    static final String PASSWORD = "S3cretPassw0rd-XYZ";

    @Autowired DigestProperties props;
    @Autowired Environment env;

    @Test
    void failedAuthenticationDoesNotLeakThePassword(CapturedOutput output) throws Exception {
        MAIL.setUser("mailuser", "someone-else");
        STUB.items = strong(1);

        assertThat(env.getProperty("digest.smtp.password")).isEqualTo(PASSWORD);
        assertThat(runner.runNow().outcome()).isEqualTo(Outcome.DELIVERY_FAILED);

        assertThat(output.getAll()).contains("not delivered").doesNotContain(PASSWORD);
        assertThat(props.toString()).doesNotContain(PASSWORD);
        assertThat(props.smtp().toString()).doesNotContain(PASSWORD);
        assertThat(MAIL.getReceivedMessages()).isEmpty();
        assertThat(sentRows()).isZero();
    }
}
