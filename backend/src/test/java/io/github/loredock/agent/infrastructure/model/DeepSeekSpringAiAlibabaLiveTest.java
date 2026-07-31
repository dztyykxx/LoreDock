package io.github.loredock.agent.infrastructure.model;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实模型接口验证 Spring AI 与 Spring AI Alibaba，不加载 LoreDock 业务服务和数据库。 */
@EnabledIfEnvironmentVariable(named = "LOREDOCK_AGENT_MODEL_API_KEY", matches = ".+")
class DeepSeekSpringAiAlibabaLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVIDENCE_ID = "8f086550-bbb8-4ec4-9769-4e87568f8477";
    private static final String OUTPUT_SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["resultType","answerBasis","text","citations","refusalReason"],
              "properties":{
                "resultType":{"type":"string","enum":["ANSWER","REFUSAL"]},
                "answerBasis":{"type":["string","null"],"enum":[null,"BUSINESS_RULE"]},
                "text":{"type":"string"},
                "citations":{"type":"array","items":{"type":"string","format":"uuid"}},
                "refusalReason":{"type":["string","null"]}
              }
            }
            """;

    /**
     * 业务目的：先确认 Spring AI 的 OpenAI 兼容客户端能从当前真实接口取得可解析 JSON，排除密钥、模型名和 JSON 模式问题。
     */
    @Test
    void springAiClientReturnsJsonObjectFromLiveModel() throws Exception {
        ChatModel model = model();

        ChatResponse response = model.call(new Prompt(
                "只返回一个 JSON 对象，包含字符串字段 status，值必须是 OK。不要输出 Markdown。"));
        String content = response.getResult().getOutput().getText();
        JsonNode result = JSON.readTree(jsonObject(content));

        assertThat(result.path("status").asText()).isEqualTo("OK");
        System.out.printf("测试证据：场景=真实模型JSON接口，响应长度=%d，JSON可解析=true，status=%s%n",
                content.length(), result.path("status").asText());
    }

    /**
     * 业务目的：确认 Spring AI Alibaba ReactAgent 能真实调用一次本地工具并返回最终结构化回答，直接暴露框架循环或终态消息兼容问题。
     */
    @Test
    void reactAgentCallsToolAndReturnsStructuredFinalMessage() throws Exception {
        AtomicInteger toolCalls = new AtomicInteger();
        ToolCallback knowledge = FunctionToolCallback.builder("knowledge_search", (SearchInput input) -> {
                    toolCalls.incrementAndGet();
                    return "证据ID=" + EVIDENCE_ID + "；项目用途=管理公开示例文档。";
                })
                .description("查询项目用途，返回可引用证据 ID")
                .inputType(SearchInput.class)
                .build();
        ReactAgent agent = ReactAgent.builder()
                .name("live-framework-smoke-" + UUID.randomUUID())
                .instruction("""
                        你必须先调用 knowledge_search 一次，然后只根据工具结果回答。
                        最终只输出符合 schema 的 JSON 对象，不输出 Markdown。
                        ANSWER 的 answerBasis 必须为 BUSINESS_RULE，并把工具返回的证据 ID 放入 citations。
                        """)
                .templateRenderer((template, values) -> template)
                .model(model())
                .tools(knowledge)
                .saver(new MemorySaver())
                .releaseThread(true)
                .parallelToolExecution(false)
                .outputSchema(OUTPUT_SCHEMA)
                .build();

        List<Message> messages = Flux.from(agent.streamMessages("这个项目是做什么的？"))
                .collectList()
                .block(Duration.ofSeconds(60));
        String finalText = aggregateFinalText(messages);

        assertThat(toolCalls).hasValue(1);
        JsonNode result = JSON.readTree(jsonObject(finalText));
        assertThat(result.path("resultType").asText()).isEqualTo("ANSWER");
        assertThat(result.path("answerBasis").asText()).isEqualTo("BUSINESS_RULE");
        assertThat(result.path("citations").get(0).asText()).isEqualTo(EVIDENCE_ID);
        System.out.printf(
                "测试证据：场景=真实ReactAgent工具闭环，工具调用=%d，消息数=%d，最终响应长度=%d，结果=%s%n",
                toolCalls.get(), messages.size(), finalText.length(), result.path("resultType").asText());
    }

    private ChatModel model() {
        String baseUrl = environment("LOREDOCK_AGENT_MODEL_BASE_URL", "https://api.deepseek.com");
        String modelName = environment("LOREDOCK_AGENT_MODEL_NAME", "deepseek-v4-flash");
        AgentProperties.Model properties = new AgentProperties.Model(
                "openai-compatible",
                modelName,
                baseUrl,
                System.getenv("LOREDOCK_AGENT_MODEL_API_KEY"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(60),
                1);
        return DeepSeekChatModelFactory.create(properties);
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String aggregateFinalText(List<Message> messages) {
        assertThat(messages).isNotNull();
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                if (assistant.hasToolCalls()) {
                    text.setLength(0);
                } else if (assistant.getText() != null) {
                    text.append(assistant.getText());
                }
            }
        }
        return text.toString();
    }

    private String jsonObject(String content) {
        assertThat(content).isNotBlank();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThanOrEqualTo(start);
        return content.substring(start, end + 1);
    }

    record SearchInput(String query) {
    }
}
