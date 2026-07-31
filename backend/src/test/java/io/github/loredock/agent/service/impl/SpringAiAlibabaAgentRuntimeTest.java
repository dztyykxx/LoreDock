package io.github.loredock.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.config.AgentRuntimeLimits;
import io.github.loredock.agent.exception.AgentExecutionException;
import io.github.loredock.agent.exception.AgentToolException;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.AgentExecutionResult;
import io.github.loredock.agent.model.result.AgentToolResult;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.agent.model.tool.CodeSearchToolRequest;
import io.github.loredock.agent.model.tool.CodeSnippetToolRequest;
import io.github.loredock.agent.model.tool.KnowledgeSearchToolRequest;
import io.github.loredock.agent.service.ProjectQaToolService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;

@ExtendWith(OutputCaptureExtension.class)
class SpringAiAlibabaAgentRuntimeTest {

    private static final Long RUN_ID = 1052023740649963522L;
    private static final Long KNOWLEDGE_ID = 1052023740649963523L;
    private static final Long CODE_ID = 1052023740649963524L;

    /**
     * 业务目的：stdout 只显示用户问题、截断后的工具结果预览和模型最终响应，避免 Prompt 与流式分片淹没联调信息。
     */
    @Test
    void realReactAgentPrintsConciseQaStages(CapturedOutput output) {
        String longKnowledgeContext = "knowledge evidence\n" + "x".repeat(1300)
                + "\nSHOULD_NOT_BE_PRINTED";
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                tool("knowledge_search", "{\"query\":\"审核规则\",\"limit\":1}"),
                tool("code_search", "{\"query\":\"ReviewService\",\"pathPrefix\":\"src\",\"limit\":1}"),
                tool("code_snippet_read", "{\"repositoryPath\":\"src/ReviewService.java\",\"startLine\":1,\"lineCount\":20}"),
                answer("""
                        {"resultType":"ANSWER","answerBasis":"MIXED","text":"审核规则由当前实现执行。",
                         "citations":["%s","%s"],"refusalReason":null,"sourceConflict":false}
                        """.formatted(KNOWLEDGE_ID, CODE_ID))), Duration.ZERO, true);
        ProjectQaToolService tools = mock(ProjectQaToolService.class);
        when(tools.knowledgeSearch(eq(RUN_ID), any(KnowledgeSearchToolRequest.class)))
                .thenReturn(toolResult(knowledgeEvidence(), longKnowledgeContext));
        when(tools.codeSearch(eq(RUN_ID), any(CodeSearchToolRequest.class)))
                .thenReturn(new AgentToolResult("code search", List.of(), 1, 0));
        when(tools.codeSnippetRead(eq(RUN_ID), any(CodeSnippetToolRequest.class)))
                .thenReturn(toolResult(codeEvidence(), "code evidence"));
        SpringAiAlibabaAgentRuntime adapter = new SpringAiAlibabaAgentRuntime(model, tools);

        AgentExecutionResult result = adapter.execute(request(RUN_ID, "为什么需要审核？", limits(8, 8, 30)));

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
        verify(tools).knowledgeSearch(eq(RUN_ID), any(KnowledgeSearchToolRequest.class));
        verify(tools).codeSearch(eq(RUN_ID), any(CodeSearchToolRequest.class));
        verify(tools).codeSnippetRead(eq(RUN_ID), any(CodeSnippetToolRequest.class));
        assertThat(output).contains(
                "project_qa.question", "为什么需要审核？",
                "project_qa.tool_result tool=knowledge_search resultCount=1 evidenceCount=1",
                "knowledge evidence", "code evidence", "...[truncated]",
                "project_qa.model_response", "审核规则由当前实现执行。");
        assertThat(output).doesNotContain(
                "SHOULD_NOT_BE_PRINTED", "project_qa.model_prompt",
                "project_qa.model_response_chunk", "project_qa.agent_messages");
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
        SpringAiAlibabaAgentRuntime adapter = new SpringAiAlibabaAgentRuntime(
                model, mock(ProjectQaToolService.class));

        AgentExecutionResult first = adapter.execute(
                request(RUN_ID, "first-private-question", limits(8, 8, 30)));
        AgentExecutionResult second = adapter.execute(
                request(8000000000000000181L, "second-question", limits(8, 8, 30)));

        assertThat(first.modelResult().resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(second.modelResult().resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(model.prompts()).hasSize(2);
        assertThat(model.prompts().get(1)).contains("second-question").doesNotContain("first-private-question");
        System.out.println("测试证据：场景=运行记忆隔离，独立运行数=2，第二运行包含前一问题=false");
    }

    /**
     * 业务目的：兼容模型把合法 JSON 放进代码块或附带简短说明的常见输出，避免有证据的回答被误判为运行失败。
     */
    @Test
    void acceptsJsonObjectWrappedByMarkdownFence() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(answer("""
                以下是结果：
                ```json
                {"resultType":"REFUSAL","answerBasis":null,"text":"当前知识库没有足够依据",
                 "citations":[],"refusalReason":"INSUFFICIENT_EVIDENCE","sourceConflict":false}
                ```
                """)), Duration.ZERO, false);
        SpringAiAlibabaAgentRuntime adapter = new SpringAiAlibabaAgentRuntime(
                model, mock(ProjectQaToolService.class));

        AgentExecutionResult result = adapter.execute(
                request(RUN_ID, "未知问题", limits(8, 8, 30)));

        assertThat(result.modelResult().resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(result.modelResult().basis()).isNull();
        assertThat(result.modelResult().refusalReason()).isEqualTo(AgentRefusalReason.INSUFFICIENT_EVIDENCE);
        System.out.println("测试证据：场景=模型JSON代码块兼容，结果=REFUSAL，解析失败=false");
    }

    /**
     * 业务目的：真实模型会把最终 JSON 拆成多个流式分块，运行时必须完整拼接后再解析，
     * 防止只保留最后一个右花括号而把有效回答误报为模型响应无效。
     */
    @Test
    void concatenatesStreamedFinalResponseBeforeParsing() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本场景只允许流式调用");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(
                        answer("{\"resultType\":\"REFUSAL\",\"answerBasis\":null,"),
                        answer("\"text\":\"当前知识库没有足够依据\",\"citations\":[],"),
                        answer("\"refusalReason\":\"INSUFFICIENT_EVIDENCE\",\"sourceConflict\":false}"));
            }
        };
        SpringAiAlibabaAgentRuntime adapter = new SpringAiAlibabaAgentRuntime(
                model, mock(ProjectQaToolService.class));

        AgentExecutionResult result = adapter.execute(
                request(RUN_ID, "未知项目规则是什么？", limits(8, 8, 30)));

        assertThat(result.modelResult().resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(result.modelResult().refusalReason()).isEqualTo(AgentRefusalReason.INSUFFICIENT_EVIDENCE);
        assertThat(result.modelResult().text()).isEqualTo("当前知识库没有足够依据");
        System.out.println("测试证据：场景=真实流式JSON聚合，分块数=3，完整解析=true，结果=REFUSAL");
    }

    /**
     * 业务目的：下一次模型调用或总步骤将突破服务端固定上限时必须停止，模型参数不能提高限制。
     */
    @Test
    void modelAndStepLimitsStopBeforeExcessCall() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                tool("knowledge_search", "{\"query\":\"loop\",\"limit\":1}"),
                tool("knowledge_search", "{\"query\":\"loop\",\"limit\":1}")), Duration.ZERO, false);
        ProjectQaToolService tools = mock(ProjectQaToolService.class);
        when(tools.knowledgeSearch(any(), any())).thenReturn(toolResult(knowledgeEvidence(), "knowledge evidence"));
        SpringAiAlibabaAgentRuntime adapter = new SpringAiAlibabaAgentRuntime(model, tools);

        assertThatThrownBy(() -> adapter.execute(
                request(RUN_ID, "持续调用", limits(8, 1, 30))))
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
     * 业务目的：允许检索已成功但始终没有合格证据时，运行必须形成明确拒答，防止模型循环被误报为系统故障。
     */
    @Test
    void successfulEmptyRetrievalAtBudgetBoundaryBecomesInsufficientEvidenceRefusal() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                tool("knowledge_search", "{\"query\":\"不存在的规则\",\"limit\":1}"),
                tool("knowledge_search", "{\"query\":\"仍然不存在\",\"limit\":1}")), Duration.ZERO, false);
        ProjectQaToolService tools = mock(ProjectQaToolService.class);
        when(tools.knowledgeSearch(any(), any())).thenReturn(new AgentToolResult("", List.of(), 0, 0));
        SpringAiAlibabaAgentRuntime adapter = new SpringAiAlibabaAgentRuntime(model, tools);

        AgentExecutionResult result = adapter.execute(
                request(RUN_ID, "目前有哪些文档？", limits(8, 1, 30)));

        assertThat(result.modelResult().resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(result.modelResult().refusalReason()).isEqualTo(AgentRefusalReason.INSUFFICIENT_EVIDENCE);
        assertThat(result.modelResult().text()).isEqualTo("当前知识库没有足够依据");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.usage().modelCallCount()).isEqualTo(1);
        assertThat(result.usage().stepCount()).isEqualTo(2);
        System.out.printf("测试证据：场景=成功空检索收敛拒答，步骤=%d，模型调用=%d，证据=%d，原因=%s%n",
                result.usage().stepCount(), result.usage().modelCallCount(), result.evidence().size(),
                result.modelResult().refusalReason());
    }

    /**
     * 业务目的：检索工具本身失败必须保留真实故障，防止用户被错误告知为知识库没有证据。
     */
    @Test
    void toolFailureIsNotConvertedToInsufficientEvidence() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                tool("knowledge_search", "{\"query\":\"规则\",\"limit\":1}")), Duration.ZERO, false);
        ProjectQaToolService tools = mock(ProjectQaToolService.class);
        when(tools.knowledgeSearch(any(), any()))
                .thenThrow(new AgentToolException(AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED));
        SpringAiAlibabaAgentRuntime adapter = new SpringAiAlibabaAgentRuntime(model, tools);

        assertThatThrownBy(() -> adapter.execute(
                request(RUN_ID, "规则是什么？", limits(8, 8, 30))))
                .isInstanceOfSatisfying(AgentExecutionException.class,
                        error -> assertThat(error.code()).isEqualTo(AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED));
        System.out.println("测试证据：场景=检索故障不转拒答，错误=AGENT_EVIDENCE_VERSION_CHANGED");
    }

    /**
     * 业务目的：未分类的检索基础设施异常必须形成安全内部错误，不能被后续编排覆盖为证据不足或运行上限。
     */
    @Test
    void unexpectedToolInfrastructureFailureKeepsSafeInternalError() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                tool("knowledge_search", "{\"query\":\"规则\",\"limit\":1}")), Duration.ZERO, false);
        ProjectQaToolService tools = mock(ProjectQaToolService.class);
        when(tools.knowledgeSearch(any(), any())).thenThrow(new IllegalStateException("private endpoint detail"));
        SpringAiAlibabaAgentRuntime adapter = new SpringAiAlibabaAgentRuntime(model, tools);

        assertThatThrownBy(() -> adapter.execute(
                request(RUN_ID, "规则是什么？", limits(8, 8, 30))))
                .isInstanceOfSatisfying(AgentExecutionException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(AgentErrorCode.AGENT_INTERNAL_ERROR);
                            assertThat(error.getMessage()).doesNotContain("private endpoint detail");
                        });
        System.out.println("测试证据：场景=检索基础设施异常，公开错误=AGENT_INTERNAL_ERROR，内部详情泄漏=false");
    }

    /**
     * 业务目的：慢流必须在运行截止时间被取消且无迟到结果；无效 JSON 必须使用稳定模型响应错误。
     */
    @Test
    void timeoutCancelsSlowStreamAndInvalidJsonUsesStableError() {
        ScriptedChatModel slow = new ScriptedChatModel(List.of(refusal()), Duration.ofMillis(300), false);
        SpringAiAlibabaAgentRuntime slowAdapter = new SpringAiAlibabaAgentRuntime(
                slow, mock(ProjectQaToolService.class));
        assertThatThrownBy(() -> slowAdapter.execute(
                request(RUN_ID, "slow", limits(8, 8, 1), Instant.now().plusMillis(50))))
                .isInstanceOfSatisfying(AgentExecutionException.class,
                        error -> assertThat(error.code()).isEqualTo(AgentErrorCode.AGENT_RUN_TIMEOUT));
        assertThat(slow.cancelled()).isEqualTo(1);
        assertThat(slow.delivered()).isZero();

        ScriptedChatModel invalid = new ScriptedChatModel(List.of(answer("not-json")), Duration.ZERO, false);
        SpringAiAlibabaAgentRuntime invalidAdapter = new SpringAiAlibabaAgentRuntime(
                invalid, mock(ProjectQaToolService.class));
        assertThatThrownBy(() -> invalidAdapter.execute(
                request(RUN_ID, "invalid", limits(8, 8, 30))))
                .isInstanceOfSatisfying(AgentExecutionException.class,
                        error -> assertThat(error.code()).isEqualTo(AgentErrorCode.AGENT_MODEL_RESPONSE_INVALID));
        System.out.printf("测试证据：场景=超时与无效结构，慢流交付=%d，取消=%d，无效JSON错误=%s%n",
                slow.delivered(), slow.cancelled(), AgentErrorCode.AGENT_MODEL_RESPONSE_INVALID);
    }

    private AgentRuntimeLimits limits(int steps, int modelCalls, int seconds) {
        return new AgentRuntimeLimits(steps, modelCalls, Duration.ofSeconds(seconds),
                10, 2000, 24000, 8000, 200);
    }

    private AgentExecutionRequest request(Long runId, String question, AgentRuntimeLimits limits) {
        return request(runId, question, limits, Instant.now().plus(limits.timeout()));
    }

    private AgentExecutionRequest request(
            Long runId,
            String question,
            AgentRuntimeLimits limits,
            Instant deadline
    ) {
        return new AgentExecutionRequest(runId, question,
                "# project_qa\n只根据工具证据回答。", "{\"type\":\"object\"}",
                new AgentScopeSnapshot(8000000000000000182L, "atlas", 8000000000000000183L, "main",
                        8000000000000000184L, "abcdef1", 8000000000000000185L,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "deepseek-v4-flash", "project-qa-v1"), limits, deadline);
    }

    private AgentToolResult toolResult(AgentEvidence evidence, String context) {
        return new AgentToolResult(context, List.of(evidence), 1, 0);
    }

    private AgentEvidence knowledgeEvidence() {
        return new AgentEvidence(KNOWLEDGE_ID, RUN_ID, EvidenceSourceType.KNOWLEDGE, true, 0.9,
                8000000000000000186L, null, "atlas", "main", null, null, "规则", Instant.now());
    }

    private AgentEvidence codeEvidence() {
        return new AgentEvidence(CODE_ID, RUN_ID, EvidenceSourceType.CODE, true, 1.0,
                null, 8000000000000000187L, "atlas", "main", "abcdef1", "src/ReviewService.java", null, Instant.now());
    }

    private static ChatResponse tool(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(Long.toString(8000000000000000188L), "function", name, arguments))).build();
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
