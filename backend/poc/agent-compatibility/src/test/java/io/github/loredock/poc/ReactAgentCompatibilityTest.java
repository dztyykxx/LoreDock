package io.github.loredock.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReactAgentCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 业务目的：证明候选正式依赖能让真实 ReactAgent 完成一次受控工具循环并返回可校验结构，
     * 防止只验证依赖可下载却在核心问答路径运行失败。
     */
    @Test
    void shouldExecuteWhitelistedToolAndReturnStructuredAnswer() throws Exception {
        PocAgentHarness harness = PocAgentHarness.toolThenAnswer();

        AssistantMessage answer = harness.agent().call("场景包导入后为什么刷新拓扑？");
        JsonNode result = objectMapper.readTree(answer.getText());

        assertThat(harness.toolQueries()).containsExactly("场景包 刷新拓扑");
        assertThat(harness.modelCalls()).isEqualTo(2);
        assertThat(result.path("resultType").asText()).isEqualTo("ANSWER");
        assertThat(result.path("citations").get(0).asText()).isEqualTo("ev-1");
        System.out.printf("POC场景=受控工具循环 modelCalls=%d toolQueries=%s resultType=%s%n",
                harness.modelCalls(), harness.toolQueries(), result.path("resultType").asText());
    }

    /**
     * 业务目的：证明 1.1.2.3 的 streamMessages 能输出最终消息，
     * 防止 T7 接入阶段才发现单 Agent 只能同步执行。
     */
    @Test
    void shouldStreamAgentMessages() throws Exception {
        PocAgentHarness harness = PocAgentHarness.directAnswer();

        List<String> messages = harness.agent().streamMessages("说明刷新原因")
                .map(message -> message.getText())
                .filter(text -> text != null && !text.isBlank())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(messages).isNotNull().anyMatch(text -> text.contains("ANSWER"));
        assertThat(harness.lastPromptContents()).contains("仅使用白名单工具并返回结构化 JSON");
        System.out.printf("POC场景=流式消息 messageCount=%d modelCalls=%d%n", messages.size(), harness.modelCalls());
    }

    /**
     * 业务目的：证明框架 Hook 会在第二次模型调用前强制停止，
     * 防止恶意或异常模型通过持续工具循环突破调用上限。
     */
    @Test
    void shouldEnforceModelCallLimitBeforeSecondCall() {
        PocAgentHarness harness = PocAgentHarness.toolLoopWithOneModelCallLimit();

        assertThatThrownBy(() -> harness.agent().call("持续调用工具"))
                .isInstanceOf(ModelCallLimitExceededException.class)
                .hasMessageContaining("Model call limits exceeded");
        assertThat(harness.modelCalls()).isEqualTo(1);
        System.out.printf("POC场景=模型调用上限 modelCalls=%d toolCalls=%d%n",
                harness.modelCalls(), harness.toolQueries().size());
    }

    /**
     * 业务目的：证明外层 Reactor 截止时间能够取消慢流且不交付迟到结果，
     * 防止超时运行在终态后继续向下游发布回答。
     */
    @Test
    void shouldCancelSlowStreamAtDeadlineAndDropLateResult() {
        PocAgentHarness harness = PocAgentHarness.slowAnswer(Duration.ofMillis(300));

        assertThatThrownBy(() -> Flux.from(harness.agent().streamMessages("慢响应"))
                        .timeout(Duration.ofMillis(50))
                        .collectList()
                        .block())
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(harness.deliveredAnswers()).isZero();
        assertThat(harness.cancelledStreams()).isEqualTo(1);
        System.out.printf("POC场景=超时取消 delivered=%d cancelled=%d%n",
                harness.deliveredAnswers(), harness.cancelledStreams());
    }
}
