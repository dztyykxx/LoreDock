package io.github.loredock.agent.service.impl;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.StreamingModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** 观察框架真实模型、Tool 与流式输出事件，不承担调用上限控制。 */
final class ProjectQaExecutionObserver extends ModelInterceptor implements StreamingModelInterceptor {

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicBoolean usageComplete = new AtomicBoolean(true);
    private final StreamingAnswerEmitter answerEmitter;
    private final AtomicReference<Usage> streamUsage = new AtomicReference<>();
    private final AtomicReference<StringBuilder> streamContent = new AtomicReference<>(new StringBuilder());

    ProjectQaExecutionObserver(Consumer<String> answerObserver, int maximumAnswerCharacters, int maximumEvents) {
        this.answerEmitter = new StreamingAnswerEmitter(answerObserver, maximumAnswerCharacters, maximumEvents);
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        modelCalls.incrementAndGet();
        return handler.call(request);
    }

    @Override
    public ChatResponse onStreamChunk(ChatResponse chunk, ModelRequest request) {
        if (chunk != null && chunk.getMetadata().getUsage() != null) {
            streamUsage.set(chunk.getMetadata().getUsage());
        }
        if (chunk != null && !chunk.getResults().isEmpty()) {
            AssistantMessage output = chunk.getResults().getFirst().getOutput();
            if (output != null && !output.hasToolCalls()
                    && output.getText() != null && !output.getText().isBlank()) {
                String value = streamContent.get().append(output.getText()).toString();
                answerEmitter.observe(value, false);
            }
        }
        return chunk;
    }

    @Override
    public ModelRequest beforeStreamCall(ModelRequest request) {
        streamUsage.set(null);
        streamContent.set(new StringBuilder());
        return request;
    }

    @Override
    public void afterStreamComplete(AssistantMessage aggregatedMessage, ModelRequest request) {
        record(streamUsage.getAndSet(null));
    }

    @Override
    public String getName() {
        return "projectQaModelMetrics";
    }

    ToolInterceptor toolInterceptor() {
        return new ToolInterceptor() {
            @Override
            public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
                toolCalls.incrementAndGet();
                return handler.call(request);
            }

            @Override
            public String getName() {
                return "projectQaToolMetrics";
            }
        };
    }

    void flushAnswer(String structuredResponse) {
        answerEmitter.observe(structuredResponse, true);
    }

    AgentExecutionUsage usage(ProjectQaTools.RunState state, long elapsedMillis) {
        return new AgentExecutionUsage(
                modelCalls.get() + toolCalls.get(), modelCalls.get(), state.retrievalCount(),
                state.trimmedCharacterCount(), inputTokens(), outputTokens(), elapsedMillis);
    }

    private void record(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage
                || usage.getPromptTokens() == null || usage.getCompletionTokens() == null) {
            usageComplete.set(false);
            return;
        }
        inputTokens.addAndGet(usage.getPromptTokens());
        outputTokens.addAndGet(usage.getCompletionTokens());
    }

    private Long inputTokens() {
        return usageComplete.get() ? inputTokens.get() : null;
    }

    private Long outputTokens() {
        return usageComplete.get() ? outputTokens.get() : null;
    }

    /** 将结构化 JSON 中已生成的 text 安全拆成有界事件，避免逐 Token 持久化撑爆事件页。 */
    private static final class StreamingAnswerEmitter {
        private static final int MAX_DELTA_CODE_POINTS = 1000;
        private static final int RESERVED_PROCESS_EVENTS = 30;

        private final Consumer<String> observer;
        private final int minimumDeltaCodePoints;
        private String published = "";

        private StreamingAnswerEmitter(Consumer<String> observer, int maximumAnswerCharacters, int maximumEvents) {
            this.observer = observer;
            int eventBudget = Math.max(1, maximumEvents - RESERVED_PROCESS_EVENTS);
            this.minimumDeltaCodePoints = Math.max(1,
                    (int) Math.ceil((double) maximumAnswerCharacters / eventBudget));
        }

        private void observe(String structuredResponse, boolean flush) {
            String current = streamedText(structuredResponse);
            if (!current.startsWith(published)) {
                return;
            }
            int pending = current.codePointCount(published.length(), current.length());
            if (pending == 0 || (!flush && pending < minimumDeltaCodePoints)) {
                return;
            }
            String remaining = current.substring(published.length());
            while (!remaining.isEmpty()) {
                int count = Math.min(remaining.codePointCount(0, remaining.length()), MAX_DELTA_CODE_POINTS);
                int end = remaining.offsetByCodePoints(0, count);
                observer.accept(remaining.substring(0, end));
                remaining = remaining.substring(end);
            }
            published = current;
        }

        private String streamedText(String value) {
            int field = value.indexOf("\"text\"");
            if (field < 0) {
                return "";
            }
            int colon = value.indexOf(':', field + 6);
            if (colon < 0) {
                return "";
            }
            int openingQuote = colon + 1;
            while (openingQuote < value.length() && Character.isWhitespace(value.charAt(openingQuote))) {
                openingQuote++;
            }
            if (openingQuote >= value.length() || value.charAt(openingQuote) != '"') {
                return "";
            }
            StringBuilder decoded = new StringBuilder();
            for (int index = openingQuote + 1; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '"') {
                    break;
                }
                if (current != '\\') {
                    decoded.append(current);
                    continue;
                }
                if (++index >= value.length()) {
                    break;
                }
                char escaped = value.charAt(index);
                switch (escaped) {
                    case '"', '\\', '/' -> decoded.append(escaped);
                    case 'b' -> decoded.append('\b');
                    case 'f' -> decoded.append('\f');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 't' -> decoded.append('\t');
                    case 'u' -> {
                        if (index + 4 >= value.length()) {
                            return decoded.toString();
                        }
                        decoded.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
                        index += 4;
                    }
                    default -> {
                        return decoded.toString();
                    }
                }
            }
            return decoded.toString();
        }
    }
}
