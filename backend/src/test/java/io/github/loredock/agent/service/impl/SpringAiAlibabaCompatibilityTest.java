package io.github.loredock.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/** 验证项目锁定的 Spring AI Alibaba Agent API 能直接承担运行时编排。 */
class SpringAiAlibabaCompatibilityTest {

    /**
     * 业务目的：ReactAgent 必须直接接受标准模型、工具、结构化输出和框架限额 Hook，防止项目重复实现一套 Agent API。
     */
    @Test
    void reactAgentAcceptsStandardModelToolSchemaAndLimitHooks() {
        ToolCallback tool = FunctionToolCallback.builder("echo", (EchoInput input) -> input.text())
                .description("返回测试输入")
                .inputType(EchoInput.class)
                .build();

        ReactAgent agent = ReactAgent.builder()
                .name("compatibility-react-agent")
                .instruction("只输出结构化结果")
                .model(mock(ChatModel.class))
                .tools(tool)
                .outputSchema("{\"type\":\"object\"}")
                .hooks(
                        ModelCallLimitHook.builder().runLimit(2).build(),
                        ToolCallLimitHook.builder().runLimit(2).build())
                .build();

        assertThat(agent.name()).isEqualTo("compatibility-react-agent");
        assertThat(tool.getToolDefinition().name()).isEqualTo("echo");
        System.out.println("测试证据：场景=ReactAgent框架能力，标准模型=true，ToolCallback=true，结构化输出=true，限额Hook=true");
    }

    /**
     * 业务目的：多 Agent 知识挖掘必须能直接使用 Flow Agent 组合现有 Agent，避免现在提前自建流程编排框架。
     */
    @Test
    void flowAgentComposesExistingAgents() {
        ChatModel model = mock(ChatModel.class);
        ReactAgent collector = ReactAgent.builder().name("collector").model(model).build();
        ReactAgent reviewer = ReactAgent.builder().name("reviewer").model(model).build();

        SequentialAgent flow = SequentialAgent.builder()
                .name("knowledge-mining-flow")
                .description("先收集再复核")
                .subAgents(List.of(collector, reviewer))
                .build();

        assertThat(flow.name()).isEqualTo("knowledge-mining-flow");
        assertThat(flow.description()).isEqualTo("先收集再复核");
        System.out.println("测试证据：场景=Flow Agent组合，子Agent数=2，组合方式=SEQUENTIAL");
    }

    private record EchoInput(String text) {
    }
}
