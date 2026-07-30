package io.github.loredock.agent.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.loredock.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekChatModelFactoryTest {

    private static final String TEST_KEY = "test-secret-not-real";
    private final List<HttpServer> servers = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    /**
     * 业务目的：DeepSeek OpenAI 兼容普通响应必须发送固定模型和 Bearer 鉴权，并解析 JSON 与实际 Token usage。
     */
    @Test
    void nonStreamingJsonResponseUsesConfiguredModelAuthAndUsage() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "application/json", normalResponse(true));
        });
        ChatModel model = DeepSeekChatModelFactory.create(properties(baseUrl(server), 0));

        var response = model.call(new Prompt("只返回JSON"));

        assertThat(authorization.get()).isEqualTo("Bearer " + TEST_KEY);
        assertThat(request.get().path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(request.get().path("stream").asBoolean()).isFalse();
        assertThat(response.getResult().getOutput().getText()).contains("REFUSAL");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(3);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(2);
        System.out.println("测试证据：场景=DeepSeek本地协议普通响应，模型=deepseek-v4-flash，鉴权头存在=true，Token=3/2");
    }

    /**
     * 业务目的：DeepSeek 流式工具参数分块必须被完整解析并可按调用 ID 重组，usage 缺失保持未知。
     */
    @Test
    void streamingToolArgumentsAreCombinedAndMissingUsageRemainsUnknown() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "text/event-stream", """
                data: {"id":"chat-1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"knowledge_search","arguments":"{\\\"query\\\":"}}]},"finish_reason":null}]}

                data: {"id":"chat-1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"","arguments":"\\\"审核\\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """));
        ChatModel model = DeepSeekChatModelFactory.create(properties(baseUrl(server), 0));

        var responses = model.stream(new Prompt("调用知识工具")).collectList().block(Duration.ofSeconds(3));

        assertThat(responses).isNotNull().isNotEmpty();
        var toolCalls = responses.stream()
                .flatMap(response -> response.getResult().getOutput().getToolCalls().stream()).toList();
        assertThat(toolCalls).hasSize(2).allSatisfy(call -> assertThat(call.id()).isEqualTo("call-1"));
        assertThat(toolCalls.stream().map(AssistantMessage.ToolCall::name).collect(java.util.stream.Collectors.joining()))
                .isEqualTo("knowledge_search");
        assertThat(toolCalls.stream().map(AssistantMessage.ToolCall::arguments)
                .collect(java.util.stream.Collectors.joining())).isEqualTo("{\"query\":\"审核\"}");
        assertThat(responses.getLast().getMetadata().getUsage())
                .isInstanceOf(org.springframework.ai.chat.metadata.EmptyUsage.class);
        System.out.println("测试证据：场景=DeepSeek本地协议流式工具，参数分块=2，同一调用ID=true，可重组调用=1，Token=未知");
    }

    /**
     * 业务目的：429 只进行有限重试，鉴权/无效 JSON/连接失败必须终止且异常不得泄露 API Key。
     */
    @Test
    void transientRetryAndProtocolFailuresAreFiniteAndSecretSafe() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer retryServer = server(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                respond(exchange, 429, "application/json", errorResponse("rate_limit"));
            } else {
                respond(exchange, 200, "application/json", normalResponse(false));
            }
        });
        ChatModel retrying = DeepSeekChatModelFactory.create(properties(baseUrl(retryServer), 1));
        assertThat(retrying.call(new Prompt("retry")).getResult().getOutput().getText()).contains("REFUSAL");
        assertThat(attempts.get()).isEqualTo(2);

        AtomicInteger authAttempts = new AtomicInteger();
        HttpServer authServer = server(exchange -> {
            authAttempts.incrementAndGet();
            respond(exchange, 401, "application/json", errorResponse("authentication_error"));
        });
        assertSecretSafe(() -> DeepSeekChatModelFactory.create(properties(baseUrl(authServer), 1))
                .call(new Prompt("auth")));
        assertThat(authAttempts.get()).isEqualTo(1);

        HttpServer invalidServer = server(exchange -> respond(exchange, 200, "application/json", "not-json"));
        assertSecretSafe(() -> DeepSeekChatModelFactory.create(properties(baseUrl(invalidServer), 0))
                .call(new Prompt("invalid")));
        assertSecretSafe(() -> DeepSeekChatModelFactory.create(
                new AgentProperties.Model("openai-compatible", "deepseek-v4-flash",
                        "http://127.0.0.1:1", TEST_KEY, Duration.ofMillis(100), Duration.ofSeconds(1), 0))
                .call(new Prompt("connection")));
        System.out.printf("测试证据：场景=DeepSeek本地协议失败，429尝试=%d，鉴权尝试=%d，无效JSON/连接失败=已终止，密钥泄露=false%n",
                attempts.get(), authAttempts.get());
    }

    private void assertSecretSafe(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).satisfies(error -> assertThat(stack(error)).doesNotContain(TEST_KEY));
    }

    private String stack(Throwable error) {
        StringBuilder value = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            value.append(current.getClass().getName()).append(':').append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return value.toString();
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        servers.add(server);
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AgentProperties.Model properties(String baseUrl, int retries) {
        return new AgentProperties.Model("openai-compatible", "deepseek-v4-flash", baseUrl, TEST_KEY,
                Duration.ofSeconds(1), Duration.ofSeconds(3), retries);
    }

    private String normalResponse(boolean usage) {
        return """
                {"id":"chat-1","object":"chat.completion","created":1,"model":"deepseek-v4-flash",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"{\\\"resultType\\\":\\\"REFUSAL\\\"}"},"finish_reason":"stop"}]%s}
                """.formatted(usage ? ",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}" : "");
    }

    private String errorResponse(String type) {
        return "{\"error\":{\"message\":\"request rejected\",\"type\":\"" + type + "\",\"code\":\"rejected\"}}";
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
