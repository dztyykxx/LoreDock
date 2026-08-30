package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.context.ContextBudget;
import io.github.loredock.agent.model.enums.ContextMode;
import io.github.loredock.agent.model.context.ContextSummaryState;
import io.github.loredock.agent.model.context.ConversationContext;
import io.github.loredock.agent.model.context.PreparedModelContext;
import io.github.loredock.agent.model.context.WorkflowContext;
import io.github.loredock.agent.model.enums.AgentNode;
import io.github.loredock.agent.model.enums.ContextPurpose;
import io.github.loredock.agent.model.request.ContextAssemblyRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 上下文组装服务单元测试：验证最小语义上下文矩阵、确定性压缩可复现性与摘要/引用约束。
 */
class ContextAssemblyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String RAW_RETRIEVAL_MARKER = "\"issueType\":\"MISSING\"";
    private static final String RAW_TOOL_MARKER = "toolCallId";

    private final ContextAssemblyService assembly = ContextAssemblyFixtures.assembly(OBJECT_MAPPER);

    /** 业务目的：CHAT 目的只接收会话摘要、角色化轮次、当前指令与已确认决定，不携带检索/专家旧 JSON。 */
    @Test
    void chatPurposeKeepsOnlyBaseConversationContent() {
        ContextAssemblyRequest request = request(AgentNode.MAIN_AGENT, ContextPurpose.CHAT,
                List.of(new ConversationContext.DialogueTurn("USER", "第一轮用户指令"),
                        new ConversationContext.DialogueTurn("ASSISTANT", "第一轮结论"),
                        new ConversationContext.DialogueTurn("USER", "第二轮用户指令"),
                        new ConversationContext.DialogueTurn("ASSISTANT", "第二轮结论")),
                conversationWithDecisions(),
                new WorkflowContext(List.of(), List.of(), List.of(), List.of(), null, null, null, List.of()));

        PreparedModelContext prepared = assembly.assemble(request, noSummary(), 0, mock(ChatModel.class))
                .prepared();

        String text = text(prepared.messages());
        assertThat(prepared.receipt().mode()).isEqualTo(ContextMode.FULL);
        assertThat(text).contains("【当前指令】测试目标");
        assertThat(text).contains("已截断");
        assertThat(text).contains("decision-1");
        assertThat(text).contains("第一轮用户指令").contains("第二轮结论");
        assertThat(text).doesNotContain(RAW_RETRIEVAL_MARKER).doesNotContain(RAW_TOOL_MARKER);
        System.out.printf("测试证据：场景=CHAT最小上下文，含当前指令/决定/角色化历史=%s，不含检索原文=%s%n",
                text.contains("decision-1"), !text.contains(RAW_RETRIEVAL_MARKER));
    }

    /** 业务目的：DRAFT 目的包含草稿基线 revision 与写入指令，且不含 Retriever 原文。 */
    @Test
    void draftPurposeIncludesBaselineRevisionAndSkipsRetrieverRaw() {
        WorkflowContext workflow = new WorkflowContext(
                List.of(new WorkflowContext.SupportedFact("合并背景", List.of("EVIDENCE:88"))),
                List.of(), List.of(new WorkflowContext.SourceReference("EVIDENCE", "88")),
                List.of(new WorkflowContext.DraftReference("14", 1)),
                new WorkflowContext.DraftInstruction("项目资料/02-导入导出", "将草稿 7 和草稿 9 合并为一篇"),
                null, null, List.of());

        PreparedModelContext prepared = assembly.assemble(
                request(AgentNode.DRAFTER, ContextPurpose.FULL_CURATION_DRAFT, List.of(), conversation(),
                        workflow), noSummary(), 0, mock(ChatModel.class)).prepared();

        String text = text(prepared.messages());
        assertThat(text).contains("draftRef: 14 revision=1");
        assertThat(text).contains("【允许处理的事实与引用】");
        assertThat(text).doesNotContain(RAW_RETRIEVAL_MARKER);
        System.out.printf("测试证据：场景=DRAFT最小上下文，含基线revision=%s，无检索原文=%s%n",
                text.contains("revision=1"), !text.contains(RAW_RETRIEVAL_MARKER));
    }

    /** 业务目的：REPAIR 入口只携带最小输入 + 有界错误摘要 + lastValidatedNode，修复尝试信息可见。 */
    @Test
    void repairPurposeCarriesBoundedErrorAndLastValidatedNode() {
        WorkflowContext workflow = new WorkflowContext(List.of(), List.of(), List.of(), List.of(), null, null,
                new WorkflowContext.RetryContext(1, "coordinator", "解析失败：字段缺失"), List.of());

        PreparedModelContext prepared = assembly.assemble(
                request(AgentNode.COORDINATOR, ContextPurpose.REPAIR, List.of(), conversation(), workflow),
                noSummary(), 0, mock(ChatModel.class)).prepared();

        String text = text(prepared.messages());
        assertThat(text).contains("lastValidatedNode=coordinator");
        assertThat(text).contains("attempt=1");
        assertThat(text).contains("解析失败");
        System.out.printf("测试证据：场景=REPAIR最小输入，含lastValidatedNode=%s，含错误摘要=%s%n",
                text.contains("lastValidatedNode=coordinator"), text.contains("解析失败"));
    }

    /** 业务目的：确定性压缩只删最旧完整轮次、半轮不截断，且同一输入两次压缩结果完全一致（可复现）。 */
    @Test
    void deterministicCompressionIsReproducibleAndKeepsProtectedOutput() {
        ContextBudget tiny = new ContextBudget(2000, 2000, 100, 50, 800, 600, 512000, 1, 3);
        ContextAssemblyService tinyAssembly = new ContextAssemblyService(
                mock(KnowledgeTaskConversationMapper.class), mock(KnowledgeTaskMessageMapper.class), tiny,
                new ContextTokenEstimator(), new ContextCompressionService(OBJECT_MAPPER,
                mock(KnowledgeTaskMessageMapper.class), new ContextTokenEstimator()));
        // 4 轮长历史（每轮约 600 字符），触发阈值 800 token 且允许的历史预算远小于全部历史。
        List<ConversationContext.DialogueTurn> history = List.of(
                new ConversationContext.DialogueTurn("USER", "旧轮一用户" + "长".repeat(300)),
                new ConversationContext.DialogueTurn("ASSISTANT", "旧轮一回复" + "长".repeat(300)),
                new ConversationContext.DialogueTurn("USER", "旧轮二用户" + "长".repeat(300)),
                new ConversationContext.DialogueTurn("ASSISTANT", "旧轮二回复" + "长".repeat(300)));

        PreparedModelContext first = tinyAssembly.assemble(
                request(AgentNode.MAIN_AGENT, ContextPurpose.CHAT, history, conversation(),
                        new WorkflowContext(List.of(), List.of(), List.of(), List.of(), null, null, null, List.of())),
                noSummary(), 0, mock(ChatModel.class)).prepared();
        PreparedModelContext second = tinyAssembly.assemble(
                request(AgentNode.MAIN_AGENT, ContextPurpose.CHAT, history, conversation(),
                        new WorkflowContext(List.of(), List.of(), List.of(), List.of(), null, null, null, List.of())),
                noSummary(), 0, mock(ChatModel.class)).prepared();

        String firstText = text(first.messages());
        assertThat(first.receipt().mode()).isEqualTo(ContextMode.DETERMINISTIC);
        assertThat(first.receipt().droppedHistoryTurns()).isGreaterThan(0);
        assertThat(firstText).isEqualTo(text(second.messages()));
        // 保护块（当前指令）与最新完整轮次保留；半轮不截断：历史消息长度必须为偶数。
        assertThat(text(first.messages())).contains("测试目标");
        System.out.printf("测试证据：场景=确定性压缩，mode=%s，丢弃历史轮=%d，结果可复现=%s%n",
                first.receipt().mode(), first.receipt().droppedHistoryTurns(),
                firstText.equals(text(second.messages())));
    }

    /** 业务目的：估算器按 UTF-8 字节上界估算并如实标注模式；空链估算为 0。 */
    @Test
    void estimatorUsesUtf8ByteBoundAndLabelsMode() {
        ContextTokenEstimator estimator = new ContextTokenEstimator();
        String mixed = "知识整理" + "context assembly" + "✓".repeat(5);
        var estimate = estimator.estimate(List.of(new UserMessage(mixed)));
        int expected = (int) Math.ceil(mixed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 3.0);
        assertThat(estimate.tokens()).isEqualTo(expected);
        assertThat(estimate.mode()).isEqualTo(ContextTokenEstimator.UTF8_BYTE_BOUND);
        assertThat(estimator.estimate(List.of()).tokens()).isZero();
        System.out.printf("测试证据：场景=估算器，混合文本估算=%d（期望%d），模式=%s%n",
                estimate.tokens(), expected, estimate.mode());
    }

    private static ContextAssemblyRequest request(
            AgentNode node, ContextPurpose purpose, List<ConversationContext.DialogueTurn> history,
            ConversationContext conversation, WorkflowContext workflow
    ) {
        if (history != null) {
            conversation = new ConversationContext(conversation.originalGoal(), history,
                    conversation.confirmedDecisions(), conversation.pendingAdministratorGuidance(),
                    conversation.historyTruncated());
        }
        return new ContextAssemblyRequest(1L, 2L, node, purpose, "测试目标",
                conversation, workflow, ContextAssemblyFixtures.budget());
    }

    private static ConversationContext conversation() {
        return new ConversationContext("整理项目知识", List.of(), List.of(), null, false);
    }

    private static ConversationContext conversationWithDecisions() {
        return new ConversationContext("整理项目知识", List.of(),
                List.of(new ConversationContext.ConfirmedDecision("decision-1", "SCENARIO_ID_CONFLICT 以已发布规则为准")),
                null, true);
    }

    private static ContextSummaryState noSummary() {
        return new ContextSummaryState(null, 0L, null, null, 0);
    }

    private static String text(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        messages.forEach(message -> builder.append(message.getText()).append('\n'));
        return builder.toString();
    }
}
