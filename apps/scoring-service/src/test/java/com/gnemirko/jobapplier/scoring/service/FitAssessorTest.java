package com.gnemirko.jobapplier.scoring.service;

import static com.gnemirko.jobapplier.scoring.service.UnitSupport.posting;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import com.gnemirko.jobapplier.scoring.FakeChatModel;
import com.gnemirko.jobapplier.scoring.dto.BuiltPrompt;
import com.gnemirko.jobapplier.scoring.dto.CallResult;
import com.gnemirko.jobapplier.scoring.dto.CallResult.Kind;

class FitAssessorTest {

    private final FakeChatModel model = new FakeChatModel();
    private final FitAssessor assessor = new FitAssessor(ChatClient.create(model), UnitSupport.profile());
    private final BuiltPrompt prompt = new PromptBuilder(UnitSupport.profile(), UnitSupport.props(1, 1, 500, "1"),
            assessor, new ChatOptionsFactory(UnitSupport.props(1, 1, 500, "1"), new org.springframework.mock.env.MockEnvironment())).build(posting("Dev", "Acme", "Remote", "desc"));

    @Test
    void validReplyUsesProviderUsage() {
        model.reply(FakeChatModel.VALID, 321, 45);
        CallResult r = assessor.assess(prompt);
        assertThat(r.kind()).isEqualTo(Kind.VALID);
        assertThat(r.inputTokens()).isEqualTo(321);
        assertThat(r.outputTokens()).isEqualTo(45);
        assertThat(r.estimated()).isFalse();
        assertThat(r.assessment().metMustHaves()).containsExactly(1, 2);
    }

    @Test
    void replyInsideCodeFenceIsAccepted() {
        model.reply("```json\n" + FakeChatModel.VALID + "\n```", 10, 10);
        assertThat(assessor.assess(prompt).kind()).isEqualTo(Kind.VALID);
    }

    @Test
    void invalidReplyIsStillChargedFromUsage() {
        model.reply("I cannot help with that", 400, 8);
        CallResult r = assessor.assess(prompt);
        assertThat(r.kind()).isEqualTo(Kind.INVALID);
        assertThat(r.charged()).isTrue();
        assertThat(r.inputTokens()).isEqualTo(400);
    }

    @Test
    void missingFieldsAreInvalid() {
        model.reply("{\"tier\":\"STRONG\"}", 10, 10);
        assertThat(assessor.assess(prompt).kind()).isEqualTo(Kind.INVALID);
        model.reply("{\"tier\":\"STRONG\",\"interviewChance\":\"LIKELY\",\"reason\":\"  \"}", 10, 10);
        assertThat(assessor.assess(prompt).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void missingUsageIsEstimatedPerSide() {
        model.reply(FakeChatModel.VALID, null, 50);
        CallResult r = assessor.assess(prompt);
        assertThat(r.inputTokens()).isEqualTo(prompt.estimatedTokens());
        assertThat(r.outputTokens()).isEqualTo(50);
        assertThat(r.estimated()).isTrue();
    }

    @Test
    void numbersForEnumsAndNonStringTextAreInvalid() {
        model.reply("{\"tier\":0,\"interviewChance\":\"POSSIBLE\",\"reason\":\"x\"}", 10, 10);
        assertThat(assessor.assess(prompt).kind()).isEqualTo(Kind.INVALID);
        model.reply("{\"tier\":\"WEAK\",\"interviewChance\":\"POSSIBLE\",\"reason\":97}", 10, 10);
        assertThat(assessor.assess(prompt).kind()).isEqualTo(Kind.INVALID);
        model.reply("{\"tier\":\"WEAK\",\"interviewChance\":\"POSSIBLE\",\"reason\":\"x\",\"metMustHaves\":[\"1\"]}", 10, 10);
        assertThat(assessor.assess(prompt).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void otherClientErrorsAreRejectedRequests() {
        model.fail(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "insufficient_quota", HttpHeaders.EMPTY, new byte[0], null));
        CallResult r = assessor.assess(prompt);
        assertThat(r.kind()).isEqualTo(Kind.REQUEST_REJECTED);
        assertThat(r.charged()).isFalse();
    }

    @Test
    void failuresAreClassifiedNotThrown() {
        model.fail(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "no", HttpHeaders.EMPTY, new byte[0], null));
        CallResult auth = assessor.assess(prompt);
        assertThat(auth.kind()).isEqualTo(Kind.AUTH_FAILURE);
        assertThat(auth.charged()).isFalse();

        model.fail(new RuntimeException("x", new SocketTimeoutException("timed out")));
        CallResult timeout = assessor.assess(prompt);
        assertThat(timeout.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(timeout.charged()).isTrue();
        assertThat(timeout.inputTokens()).isEqualTo(prompt.estimatedTokens());
        assertThat(timeout.outputTokens()).isZero();

        model.fail(new IllegalStateException("500"));
        CallResult other = assessor.assess(prompt);
        assertThat(other.kind()).isEqualTo(Kind.PROVIDER_FAILURE);
        assertThat(other.charged()).isFalse();
    }
}
