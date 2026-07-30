package io.github.loredock.agent.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekLiveSmokeTest {

    /**
     * 业务目的：仅在人工显式开启时执行一次最小真实调用，验证 DeepSeek 鉴权、模型响应、JSON 解析和 usage 链路。
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "LOREDOCK_DEEPSEEK_LIVE", matches = "true")
    void onePaidNonStreamingJsonCallVerifiesProductionProtocol() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey).as("真实 smoke 仅从进程环境读取密钥").isNotBlank();
        var properties = new AgentProperties.Model(
                "openai-compatible", "deepseek-v4-flash", "https://api.deepseek.com", apiKey,
                Duration.ofSeconds(5), Duration.ofSeconds(30), 0);
        long started = System.nanoTime();

        var response = DeepSeekChatModelFactory.create(properties)
                .call(new Prompt("只返回一个 JSON 对象，且仅含 ok=true。"));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        var json = new ObjectMapper().readTree(response.getResult().getOutput().getText());
        assertThat(json.path("ok").asBoolean()).isTrue();
        boolean usageKnown = response.getMetadata().getUsage() != null
                && !(response.getMetadata().getUsage() instanceof org.springframework.ai.chat.metadata.EmptyUsage)
                && response.getMetadata().getUsage().getPromptTokens() != null
                && response.getMetadata().getUsage().getCompletionTokens() != null;
        assertThat(elapsedMillis).isPositive();
        System.out.printf("测试证据：场景=DeepSeek真实最小smoke，模型=deepseek-v4-flash，鉴权=true，JSON=true，Token可用=%s，耗时毫秒=%d%n",
                usageKnown, elapsedMillis);
    }
}
