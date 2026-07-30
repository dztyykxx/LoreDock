package io.github.loredock.poc;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class PocAgentHarness {

    private static final String STRUCTURED_ANSWER = """
            {"resultType":"ANSWER","answerBasis":"BUSINESS_RULE","answer":"刷新拓扑用于让导入后的关系生效。","citations":["ev-1"]}
            """;

    private final ReactAgent agent;
    private final FakeChatModel model;
    private final List<String> toolQueries;

    private PocAgentHarness(ReactAgent agent, FakeChatModel model, List<String> toolQueries) {
        this.agent = agent;
        this.model = model;
        this.toolQueries = toolQueries;
    }

    static PocAgentHarness toolThenAnswer() {
        List<String> queries = new ArrayList<>();
        FakeChatModel model = new FakeChatModel(List.of(toolCall(), answer(STRUCTURED_ANSWER)), Duration.ZERO);
        ToolCallback tool = knowledgeSearchTool(queries);
        return new PocAgentHarness(buildAgent(model, tool), model, queries);
    }

    static PocAgentHarness directAnswer() {
        FakeChatModel model = new FakeChatModel(List.of(answer(STRUCTURED_ANSWER)), Duration.ZERO);
        return new PocAgentHarness(buildAgent(model), model, new ArrayList<>());
    }

    static PocAgentHarness toolLoopWithOneModelCallLimit() {
        List<String> queries = new ArrayList<>();
        FakeChatModel model = new FakeChatModel(List.of(toolCall(), toolCall()), Duration.ZERO);
        ModelCallLimitHook limit = ModelCallLimitHook.builder()
                .runLimit(1)
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build();
        ReactAgent agent = ReactAgent.builder()
                .name("project-qa-poc")
                .instruction("仅使用白名单工具并返回结构化 JSON。")
                .model(model)
                .tools(knowledgeSearchTool(queries))
                .hooks(limit)
                .saver(new MemorySaver())
                .build();
        return new PocAgentHarness(agent, model, queries);
    }

    static PocAgentHarness slowAnswer(Duration delay) {
        FakeChatModel model = new FakeChatModel(List.of(answer(STRUCTURED_ANSWER)), delay);
        return new PocAgentHarness(buildAgent(model), model, new ArrayList<>());
    }

    ReactAgent agent() {
        return agent;
    }

    int modelCalls() {
        return model.calls.get();
    }

    List<String> toolQueries() {
        return List.copyOf(toolQueries);
    }

    int deliveredAnswers() {
        return model.delivered.get();
    }

    int cancelledStreams() {
        return model.cancelled.get();
    }

    String lastPromptContents() {
        return model.lastPromptContents;
    }

    private static ReactAgent buildAgent(FakeChatModel model, ToolCallback... tools) {
        return ReactAgent.builder()
                .name("project-qa-poc")
                .instruction("仅使用白名单工具并返回结构化 JSON。")
                .model(model)
                .tools(tools)
                .saver(new MemorySaver())
                .build();
    }

    private static ToolCallback knowledgeSearchTool(List<String> queries) {
        return FunctionToolCallback.builder("knowledge_search", (SearchRequest request) -> {
                    queries.add(request.query());
                    return Map.of("evidenceId", "ev-1", "summary", "导入完成后刷新拓扑使关系生效");
                })
                .description("在当前项目范围内检索知识证据")
                .inputType(SearchRequest.class)
                .build();
    }

    private static ChatResponse toolCall() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "knowledge_search", "{\"query\":\"场景包 刷新拓扑\"}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    record SearchRequest(String query) {
    }

    private static final class FakeChatModel implements ChatModel {

        private final List<ChatResponse> responses;
        private final Duration streamDelay;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private volatile String lastPromptContents = "";

        private FakeChatModel(List<ChatResponse> responses, Duration streamDelay) {
            this.responses = responses;
            this.streamDelay = streamDelay;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPromptContents = prompt.getContents();
            return nextResponse();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            lastPromptContents = prompt.getContents();
            ChatResponse response = nextResponse();
            return Flux.just(response)
                    .delaySubscription(streamDelay)
                    .doOnNext(ignored -> delivered.incrementAndGet())
                    .doOnCancel(cancelled::incrementAndGet);
        }

        private ChatResponse nextResponse() {
            int index = calls.getAndIncrement();
            return responses.get(Math.min(index, responses.size() - 1));
        }
    }
}
