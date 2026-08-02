package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.api.KnowledgeDraftException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

/** 验证知识整理执行器保留业务 Tool 的稳定失败语义。 */
class KnowledgeCurationRunExecutorTest {

    /**
     * 业务目的：草稿越界必须显示为 Tool 范围错误；
     * 防止管理员被误导为模型响应格式损坏。
     */
    @Test
    void draftScopeViolationMapsToToolScopeFailure() {
        String code = KnowledgeCurationRunExecutor.errorCode(new RuntimeException(
                new KnowledgeDraftException(KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION)));

        assertThat(code).isEqualTo("AGENT_TOOL_SCOPE_VIOLATION");
        System.out.println("测试证据：场景=知识草稿范围越界，公开错误码=AGENT_TOOL_SCOPE_VIOLATION");
    }

    /**
     * 业务目的：模型提交了无效区块操作时应收到稳定错误并自行修正，只有范围越界才终止整轮；
     * 防止一次可恢复的 draft_update 参数错误被误报为模型响应损坏并留下空 v0。
     */
    @Test
    void recoverableDraftValidationReturnsToModelButScopeViolationStillFails() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("draft_update").description("更新草稿").inputSchema("{}").build();
        ToolExecutionException invalidOperation = new ToolExecutionException(definition,
                new KnowledgeDraftException(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID));
        ToolExecutionException scopeViolation = new ToolExecutionException(definition,
                new KnowledgeDraftException(KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION));

        assertThat(KnowledgeCurationRunExecutor.toolExceptionProcessor().process(invalidOperation))
                .isEqualTo("TOOL_ERROR: DRAFT_OPERATION_INVALID");
        assertThatThrownBy(() -> KnowledgeCurationRunExecutor.toolExceptionProcessor().process(scopeViolation))
                .isInstanceOf(KnowledgeDraftException.class)
                .hasMessage("DRAFT_SCOPE_VIOLATION");
        System.out.println("测试证据：场景=草稿 Tool 自纠，可恢复错误=返回模型，范围越界=终止运行");
    }

    /**
     * 业务目的：模型在调用 Tool 前给管理员的简短公开说明必须进入任务对话；
     * 防止运行中页面只有工具事件，看不到设计稿要求的协调 Agent 阶段消息。
     */
    @Test
    void toolCallingAssistantTextBecomesPublicProgressMessage() {
        AssistantMessage progress = AssistantMessage.builder()
                .content("我会先核对适用版本，再检查冲突。")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "knowledge_search", "{}")))
                .build();

        assertThat(KnowledgeCurationRunExecutor.publicProgressText(progress))
                .isEqualTo("我会先核对适用版本，再检查冲突。");
        assertThat(KnowledgeCurationRunExecutor.publicProgressText(new AssistantMessage("最终结果"))).isNull();
        System.out.println("测试证据：场景=协调Agent公开进度，Tool前摘要=已投影，最终消息=单独保存");
    }
}
