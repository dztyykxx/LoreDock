package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;

/** 验证知识整理直接使用框架 Skill Registry 和 SkillsAgentHook，不再依赖 Agent Spec。 */
class KnowledgeAgentDefinitionFrameworkTest {

    /**
     * 业务目的：知识整理 Workflow Skill 必须随应用产物发布并可从 classpath 加载；
     * 防止 IDE、命令行和部署环境工作目录不同导致合并整理无法启动。
     */
    @Test
    void bundledKnowledgeCuratorSkillLoadsFromClasspath() throws Exception {
        ClasspathSkillRegistry registry = ClasspathSkillRegistry.builder()
                .classpathPath("agent-skills")
                .build();

        assertThat(registry.contains("knowledge-curator")).isTrue();
        assertThat(registry.readSkillContent("knowledge-curator"))
                .contains("selected_draft_list", "draft_update");
        System.out.println("测试证据：场景=内置知识整理 Skill，Registry=classpath，Skill=knowledge-curator");
    }

    /**
     * 业务目的：业务 Tool 必须等模型真实读取目标 Skill 后才可见；
     * 防止 Agent 未接受工作流约束就调用草稿写入。
     */
    @Test
    void skillHookDisclosesGroupedToolsOnlyAfterReadSkill() throws Exception {
        ClasspathSkillRegistry registry = ClasspathSkillRegistry.builder()
                .classpathPath("agent-skills")
                .build();
        ToolCallback selectedDraftRead = tool("selected_draft_read");
        ToolCallback draftUpdate = tool("draft_update");
        var definition = new KnowledgeAgentDefinitionService.LoadedDefinition(
                new RuntimeDefinition("knowledge-curator", "skill-digest", "empty-spec-digest", "fake-model",
                        List.of("draft_update", "selected_draft_read")),
                registry, List.of(selectedDraftRead, draftUpdate));
        SkillsAgentHook hook = definition.createSkillHook(
                new StaticToolCallbackResolver(List.of(selectedDraftRead, draftUpdate)));
        var interceptor = hook.getModelInterceptors().getFirst();
        AtomicReference<ModelRequest> observed = new AtomicReference<>();

        interceptor.interceptModel(request(List.of()), value -> {
            observed.set(value);
            return ModelResponse.of(new AssistantMessage("before"));
        });
        assertThat(observed.get().getDynamicToolCallbacks()).isEmpty();

        AssistantMessage activation = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("skill-call-1", "function", "read_skill",
                        "{\"skill_name\":\"knowledge-curator\"}"))).build();
        interceptor.interceptModel(request(List.of(activation)), value -> {
            observed.set(value);
            return ModelResponse.of(new AssistantMessage("after"));
        });

        assertThat(observed.get().getDynamicToolCallbacks())
                .extracting(value -> value.getToolDefinition().name())
                .containsExactlyInAnyOrder("selected_draft_read", "draft_update");
        assertThat(hook.getTools()).extracting(value -> value.getToolDefinition().name())
                .containsExactlyInAnyOrder("read_skill", "search_skills", "disable_skill");
        System.out.println("测试证据：场景=Skill 渐进披露，激活前业务Tool=0，激活后业务Tool=2");
    }

    private ModelRequest request(List<org.springframework.ai.chat.messages.Message> messages) {
        return ModelRequest.builder()
                .systemMessage(new SystemMessage("测试系统提示"))
                .messages(messages)
                .options(ToolCallingChatOptions.builder().build())
                .build();
    }

    private ToolCallback tool(String name) {
        return FunctionToolCallback.builder(name, (EchoInput input) -> input.value())
                .description("测试工具")
                .inputType(EchoInput.class)
                .build();
    }

    private record EchoInput(String value) { }
}
