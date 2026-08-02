package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.api.KnowledgeDraftException;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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

    /**
     * 业务目的：框架在流末尾可能追加空 AssistantMessage，任务必须保留模型此前真正提出的问题；
     * 防止页面把具体待确认问题覆盖成“知识整理运行已完成”。
     */
    @Test
    void finalResponseRequiresVisibleNonToolText() {
        AssistantMessage toolProgress = AssistantMessage.builder()
                .content("我准备记录冲突。")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-2", "function", "knowledge_search", "{}")))
                .build();

        assertThat(KnowledgeCurationRunExecutor.isPublicFinalResponse(
                new AssistantMessage("请确认审计记录应保留 180 天还是 365 天？"))).isTrue();
        assertThat(KnowledgeCurationRunExecutor.isPublicFinalResponse(new AssistantMessage("  "))).isFalse();
        assertThat(KnowledgeCurationRunExecutor.isPublicFinalResponse(toolProgress)).isFalse();
        System.out.println("测试证据：场景=最终提问投影，非空对话=保留，框架空尾消息和Tool摘要=忽略");
    }

    /**
     * 业务目的：最终答案必须取模型节点完成事件中的聚合消息，而不是最后一个流式 token；
     * 防止中文回答只保存末尾标点，或因完成事件空 message 而落入固定兜底文案。
     */
    @Test
    void completedModelTurnReadsAggregatedAssistantMessageFromOriginResponse() {
        AssistantMessage answer = new AssistantMessage("已完成整理：新增 1 份文档，并记录 2 项风险。");
        ChatResponse response = new ChatResponse(List.of(new Generation(answer)));
        StreamingOutput<ChatResponse> completed = new StreamingOutput<>(
                (Message) null, response, "agentModel", "knowledge-curation", new OverAllState(),
                OutputType.AGENT_MODEL_FINISHED);
        StreamingOutput<ChatResponse> chunk = new StreamingOutput<>(
                new AssistantMessage("已"), response, "agentModel", "knowledge-curation", new OverAllState(),
                OutputType.AGENT_MODEL_STREAMING);

        assertThat(KnowledgeCurationRunExecutor.completedAssistantMessage(completed)).isSameAs(answer);
        assertThat(KnowledgeCurationRunExecutor.completedAssistantMessage(chunk)).isNull();
        System.out.println("测试证据：场景=模型轮次完成，最终结果来源=聚合 ChatResponse，增量 token=不作最终答案");
    }
}
