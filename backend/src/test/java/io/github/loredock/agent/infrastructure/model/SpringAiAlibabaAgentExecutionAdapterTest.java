package io.github.loredock.agent.infrastructure.model;

import io.github.loredock.agent.application.AgentExecutionRequest;
import io.github.loredock.agent.application.AgentExecutionResult;
import io.github.loredock.agent.application.AgentExecutionException;
import io.github.loredock.agent.application.AgentRuntimeLimits;
import io.github.loredock.agent.application.AgentToolException;
import io.github.loredock.agent.application.AgentToolResult;
import io.github.loredock.agent.application.CodeSearchToolRequest;
import io.github.loredock.agent.application.CodeSnippetToolRequest;
import io.github.loredock.agent.application.KnowledgeSearchToolRequest;
import io.github.loredock.agent.application.ProjectQaToolRegistry;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentEvidence;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.agent.domain.AnswerBasis;
import io.github.loredock.agent.domain.EvidenceSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiAlibabaAgentExecutionAdapterTest {

    private static final UUID RUN_ID = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID KNOWLEDGE_ID = UUID.fromString("91000000-0000-0000-0000-000000000002");
    private static final UUID CODE_ID = UUID.fromString("91000000-0000-0000-0000-000000000003");

    /**
     * 业务目的：真实 ReactAgent 必须按白名单完成三种工具循环、解析结构化结果并保留实际模型/工具/Token 计数。
     */
    @Test
    void realReactAgentExecutesThreeControlledToolsAndParsesStructuredResult() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                tool("knowledge_search", "{\"query\":\"审核规则\",\"limit\":1}"),
                tool("code_search", "{\"query\":\"ReviewService\",\"pathPrefix\":\"src\",\"limit\":1}"),
                tool("code_snippet_read", "{\"repositoryPath\":\"src/ReviewService.java\",\"startLine\":1,\"lineCount\":20}"),
                answer("""
                        {"resultType":"ANSWER","answerBasis":"MIXED","text":"审核规则由当前实现执行。",
                         "citations":["%s","%s"],"refusalReason":null,"sourceConflict":false}
                        """.formatted(KNOWLEDGE_ID, CODE_ID))), Duration.ZERO, true);
        ProjectQaToolRegistry tools = mock(ProjectQaToolRegistry.class);
        when(tools.execute(eq(RUN_ID), eq("knowledge_search"), any(KnowledgeSearchToolRequest.class)))
                .thenReturn(toolResult(knowledgeEvidence(), "knowledge evidence"));
        when(tools.execute(eq(RUN_ID), eq("code_search"), any(CodeSearchToolRequest.class)))
                .thenReturn(new AgentToolResult("code search", List.of(), 1, 0));
        when(tools.execute(eq(RUN_ID), eq("code_snippet_read"), any(CodeSnippetToolRequest.class)))
                .thenReturn(toolResult(codeEvidence(), "code evidence"));
        SpringAiAlibabaAgentExecutionAdapter adapter = new SpringAiAlibabaAgentExecutionAdapter(model, tools);

        AgentExecutionResult result = adapter.execute(request(RUN_ID, "为什么需要审核？", limits(8, 8, 30)),
                (type, payload) -> { });

        assertThat(result.modelResult().resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(result.modelResult().basis()).isEqualTo(AnswerBasis.MIXED);
        assertThat(result.modelResult().citationEvidenceIds()).containsExactly(KNOWLEDGE_ID, CODE_ID);
        assertThat(result.evidence()).extracting(AgentEvidence::id).containsExactly(KNOWLEDGE_ID, CODE_ID);
        assertThat(result.usage().modelCallCount()).isEqualTo(4);
        assertThat(result.usage().stepCount()).isEqualTo(7);
        assertThat(result.usage().retrievalCount()).isEqualTo(3);
        assertThat(result.usage().inputTokens()).isEqualTo(40);
        assertThat(result.usage().outputTokens()).isEqualTo(20);
        assertThat(model.prompts().getFirst()).contains("project_qa", "为什么需要审核？");
        verify(tools).execute(eq(RUN_ID), eq("knowledge_search"), any(KnowledgeSearchToolRequest.class));
        verify(tools).execute(eq(RUN_ID), eq("code_search"), any(CodeSearchToolRequest.class));
        verify(tools).execute(eq(RUN_ID), eq("code_snippet_read"), any(CodeSnippetToolRequest.class));
        System.out.printf("测试证据：场景=ReactAgent三工具循环，模型调用=%d，工具调用=%d，证据=%d，Token=%d/%d%n",
                result.usage().modelCallCount(), result.usage().stepCount() - result.usage().modelCallCount(),
                result.evidence().size(), result.usage().inputTokens(), result.usage().outputTokens());
    }

    /**
     * 业务目的：每次执行必须创建独立 ReactAgent 和记忆，后一运行的首个提示不得包含前一运行问题或证据。
     */
    @Test
    void independentRunsDoNotShareMemory() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                refusal(), refusal()), Duration.ZERO, false);
        SpringAiAlibabaAgentExecutionAdapter adapter = new SpringAiAlibabaAgentExecutionAdapter(
                model, mock(ProjectQaToolRegistry.class));

        AgentExecutionResult first = adapter.execute(
                request(RUN_ID, "first-private-question", limits(8, 8, 30)), (type, payload) -> { });
        AgentExecutionResult second = adapter.execute(
                request(UUID.randomUUID(), "second-question", limits(8, 8, 30)), (type, payload) -> { });

        assertThat(first.modelResult().resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(second.modelResult().resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(model.prompts()).hasSize(2);
        assertThat(model.prompts().get(1)).contains("second-question").doesNotContain("first-private-question");
        System.out.println("测试证据：场景=运行记忆隔离，独立运行数=2，第二运行包含前一问题=false");
    }

    /**
     * 业务目的：下一次模型调用或总步骤将突破服务端固定上限时必须停止，模型参数不能提高限制。
     */
    @Test
    void modelAndStepLimitsStopBeforeExcessCall() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                tool("knowledge_search", "{\"query\":\"loop\",\"limit\":1}"),
                tool("knowledge_search", "{\"query\":\"loop\",\"limit\":1}")), Duration.ZERO, false);
        ProjectQaToolRegistry tools = mock(ProjectQaToolRegistry.class);
        when(tools.execute(any(), any(), any())).thenReturn(new AgentToolResult("none", List.of(), 0, 0));
        SpringAiAlibabaAgentExecutionAdapter adapter = new SpringAiAlibabaAgentExecutionAdapter(model, tools);

        assertThatThrownBy(() -> adapter.execute(
                request(RUN_ID, "持续调用", limits(8, 1, 30)), (type, payload) -> { }))
                .isInstanceOfSatisfying(AgentExecutionException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(AgentErrorCode.AGENT_MODEL_CALL_LIMIT_EXCEEDED);
                            assertThat(error.usage().modelCallCount()).isEqualTo(1);
                            assertThat(error.usage().stepCount()).isEqualTo(2);
                        });
        assertThat(model.calls()).isEqualTo(1);
        System.out.printf("测试证据：场景=模型调用硬上限，上限=1，实际调用=%d，第二次调用=false%n", model.calls());
    }

    /**
     * 业务目的：慢流必须在运行截止时间被取消且无迟到结果；无效 JSON 必须使用稳定模型响应错误。
     */
    @Test
    void timeoutCancelsSlowStreamAndInvalidJsonUsesStableError() {
        ScriptedChatModel slow = new ScriptedChatModel(List.of(refusal()), Duration.ofMillis(300), false);
        SpringAiAlibabaAgentExecutionAdapter slowAdapter = new SpringAiAlibabaAgentExecutionAdapter(
                slow, mock(ProjectQaToolRegistry.class));
        assertThatThrownBy(() -> slowAdapter.execute(
                request(RUN_ID, "slow", limits(8, 8, 1), Instant.now().plusMillis(50)),
                (type, payload) -> { }))
                .isInstanceOfSatisfying(AgentExecutionException.class,
                        error -> assertThat(error.code()).isEqualTo(AgentErrorCode.AGENT_RUN_TIMEOUT));
        assertThat(slow.cancelled()).isEqualTo(1);
        assertThat(slow.delivered()).isZero();

        ScriptedChatModel invalid = new ScriptedChatModel(List.of(answer("not-json")), Duration.ZERO, false);
        SpringAiAlibabaAgentExecutionAdapter invalidAdapter = new SpringAiAlibabaAgentExecutionAdapter(
                invalid, mock(ProjectQaToolRegistry.class));
        assertThatThrownBy(() -> invalidAdapter.execute(
                request(RUN_ID, "invalid", limits(8, 8, 30)), (type, payload) -> { }))
                .isInstanceOfSatisfying(AgentExecutionException.class,
                        error -> assertThat(error.code()).isEqualTo(AgentErrorCode.AGENT_MODEL_RESPONSE_INVALID));
        System.out.printf("测试证据：场景=超时与无效结构，慢流交付=%d，取消=%d，无效JSON错误=%s%n",
                slow.delivered(), slow.cancelled(), AgentErrorCode.AGENT_MODEL_RESPONSE_INVALID);
    }

    private AgentRuntimeLimits limits(int steps, int modelCalls, int seconds) {
        return new AgentRuntimeLimits(steps, modelCalls, Duration.ofSeconds(seconds),
                10, 2000, 24000, 8000, 200);
    }

    private AgentExecutionRequest request(UUID runId, String question, AgentRuntimeLimits limits) {
        return request(runId, question, limits, Instant.now().plus(limits.timeout()));
    }

    private AgentExecutionRequest request(
            UUID runId,
            String question,
            AgentRuntimeLimits limits,
            Instant deadline
    ) {
        return new AgentExecutionRequest(runId, question,
                "# project_qa\n只根据工具证据回答。", "{\"type\":\"object\"}",
                new AgentScopeSnapshot(UUID.randomUUID(), "atlas", UUID.randomUUID(), "main",
                        UUID.randomUUID(), "abcdef1", UUID.randomUUID(),
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "a".repeat(64),
                        "openai-compatible", "deepseek-v4-flash", "project-qa-v1",
                        "project-qa-readonly-v1", "project-qa-policy-v1"), limits, deadline);
    }

    private AgentToolResult toolResult(AgentEvidence evidence, String context) {
        return new AgentToolResult(context, List.of(evidence), 1, 0);
    }

    private AgentEvidence knowledgeEvidence() {
        return new AgentEvidence(KNOWLEDGE_ID, RUN_ID, EvidenceSourceType.KNOWLEDGE, true, 0.9,
                UUID.randomUUID(), null, "atlas", "main", null, null, "规则", Instant.now());
    }

    private AgentEvidence codeEvidence() {
        return new AgentEvidence(CODE_ID, RUN_ID, EvidenceSourceType.CODE, true, 1.0,
                null, UUID.randomUUID(), "atlas", "main", "abcdef1", "src/ReviewService.java", null, Instant.now());
    }

    private static ChatResponse tool(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, arguments))).build();
        return response(message, true);
    }

    private static ChatResponse answer(String json) {
        return response(new AssistantMessage(json), true);
    }

    private static ChatResponse refusal() {
        return answer("""
                {"resultType":"REFUSAL","answerBasis":"BUSINESS_RULE","text":"当前知识库没有足够依据",
                 "citations":[],"refusalReason":"INSUFFICIENT_EVIDENCE","sourceConflict":false}
                """);
    }

    private static ChatResponse response(AssistantMessage message, boolean usage) {
        ChatResponseMetadata metadata = usage
                ? ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build()
                : ChatResponseMetadata.builder().build();
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final Duration delay;
        private final boolean includeUsage;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        private ScriptedChatModel(List<ChatResponse> responses, Duration delay, boolean includeUsage) {
            this.responses = new ArrayList<>(responses);
            this.delay = delay;
            this.includeUsage = includeUsage;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            return next();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt.getContents());
            ChatResponse next = next();
            ChatResponse value = includeUsage ? next : new ChatResponse(next.getResults());
            return Flux.just(value).delaySubscription(delay)
                    .doOnNext(ignored -> delivered.incrementAndGet())
                    .doOnCancel(cancelled::incrementAndGet);
        }

        private ChatResponse next() {
            int index = calls.getAndIncrement();
            return responses.get(Math.min(index, responses.size() - 1));
        }

        int calls() { return calls.get(); }
        int delivered() { return delivered.get(); }
        int cancelled() { return cancelled.get(); }
        List<String> prompts() { return List.copyOf(prompts); }
    }
}
