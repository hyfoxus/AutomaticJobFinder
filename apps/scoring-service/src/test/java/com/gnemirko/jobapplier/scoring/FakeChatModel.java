package com.gnemirko.jobapplier.scoring;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** Scripted model: never touches the network. Without a script it answers with a valid assessment. */
public class FakeChatModel implements ChatModel {

    public static final String VALID = """
            {"tier":"STRONG","metMustHaves":[1,2,9],"gaps":[{"text":"Kubernetes","kind":"LEARNABLE"}],\
            "interviewChance":"POSSIBLE","reason":"Good fit."}""";

    public final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final Deque<Object> script = new ArrayDeque<>();
    /** Runs at the start of every call, before the scripted answer. */
    public volatile java.util.function.Consumer<Prompt> onCall;
    /** Finish reason put on the next generations; null leaves it unset. */
    public volatile String finishReason;
    public int defaultInputTokens = 1000;
    public int defaultOutputTokens = 1000;

    public void reset() {
        prompts.clear();
        script.clear();
        defaultInputTokens = 1000;
        defaultOutputTokens = 1000;
        onCall = null;
        finishReason = null;
    }

    public synchronized FakeChatModel reply(String text, Integer in, Integer out) {
        script.add(new Reply(text, in, out));
        return this;
    }

    public synchronized FakeChatModel replyValid() {
        return reply(VALID, defaultInputTokens, defaultOutputTokens);
    }

    public synchronized FakeChatModel fail(RuntimeException e) {
        script.add(e);
        return this;
    }

    public int calls() {
        return prompts.size();
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (onCall != null) {
            onCall.accept(prompt);
        }
        Object next = script.poll();
        if (next instanceof RuntimeException e) {
            throw e;
        }
        Reply r = next instanceof Reply rr ? rr : new Reply(VALID, defaultInputTokens, defaultOutputTokens);
        ChatResponseMetadata.Builder meta = ChatResponseMetadata.builder();
        if (r.in != null || r.out != null) {
            meta.usage(new DefaultUsage(r.in, r.out));
        }
        return ChatResponse.builder()
                .generations(new ArrayList<>(List.of(finishReason == null ? new Generation(new AssistantMessage(r.text))
                        : new Generation(new AssistantMessage(r.text),
                                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(finishReason).build()))))
                .metadata(meta.build())
                .build();
    }

    private record Reply(String text, Integer in, Integer out) {
    }
}
