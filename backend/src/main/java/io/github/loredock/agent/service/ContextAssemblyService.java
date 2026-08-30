package io.github.loredock.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.request.ContextAssemblyRequest;
import io.github.loredock.agent.model.context.ContextBudget;
import io.github.loredock.agent.model.enums.ContextMode;
import io.github.loredock.agent.model.enums.ContextPurpose;
import io.github.loredock.agent.model.context.ContextReceipt;
import io.github.loredock.agent.model.context.ContextSummaryState;
import io.github.loredock.agent.model.context.ConversationContext;
import io.github.loredock.agent.model.context.PreparedModelContext;
import io.github.loredock.agent.model.context.WorkflowContext;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 知识整理上下文组装服务（设计文档 §4-§8）：节点入口按 {@code agentNode × purpose} 组装最小语义消息。
 *
 * <p>负责：① 按 §5.1 稳定前缀在前、动态后缀在后的顺序生成消息（前缀字节级可控，不写入 runId、
 * 时间戳、receipt 等）；② 预算判定（FULL / 确定性压缩 / LLM 压缩 / BLOCKED）；③ 会话摘要的
 * 校验、滚动与重建（状态字段随父图 Checkpoint 持久化）。输入侧禁止接收父图原始 {@code messages}
 * 或完整 Tool 回执——正文一律以稳定引用表达，正文读取仍由受限业务 Tool 承担。</p>
 */
public class ContextAssemblyService {

    private static final Logger log = LoggerFactory.getLogger(ContextAssemblyService.class);

    /** 消息布局：顺序化消息与历史轮次区间（供确定性压缩仅动历史区间）。 */
    record MessageLayout(List<Message> messages, int historyStart, int historyEnd) {
    }

    /** 组装结果：模型输入 + 需要写回父图的 REPLACE 状态更新（摘要字段与压缩调用计数）。 */
    public record AssemblyResult(PreparedModelContext prepared, Map<String, Object> stateUpdates) {
    }

    /** Graph State 中 LLM 压缩兜底调用计数的键（按 run 重置，REPLACE 写入）。 */
    public static final String COMPRESSION_CALLS_KEY = "contextCompressionCalls";

    private final KnowledgeTaskConversationMapper conversations;
    private final KnowledgeTaskMessageMapper messages;
    private final ContextBudget budget;
    private final ContextTokenEstimator estimator;
    private final ContextDeterministicCompressor compressor;
    private final ContextCompressionService compressionService;

    public ContextAssemblyService(
            KnowledgeTaskConversationMapper conversations,
            KnowledgeTaskMessageMapper messages,
            ContextBudget budget,
            ContextTokenEstimator estimator,
            ContextCompressionService compressionService
    ) {
        this.conversations = conversations;
        this.messages = messages;
        this.budget = budget;
        this.estimator = estimator;
        this.compressionService = compressionService;
        this.compressor = new ContextDeterministicCompressor(estimator);
    }

    /** @return 本服务承载的预算配置（准备节点与组装共用同一常量）。 */
    public ContextBudget budget() {
        return budget;
    }

    /**
     * 组装当前节点入口的自语义上下文。
     *
     * @param request 组装请求（由工厂准备节点从 Graph State 投影）
     * @param summaryState 当前摘要状态（无摘要时传空记录）
     * @param compressionCallsUsed 该 run 已发起的 LLM 压缩调用计数（来自 state）
     * @param model 本轮共享模型（仅压缩兜底使用；无 Tool、无 Saver）
     */
    public AssemblyResult assemble(
            ContextAssemblyRequest request,
            ContextSummaryState summaryState,
            int compressionCallsUsed,
            ChatModel model
    ) {
        MessageLayout layout = buildMessages(request, usableSummary(request, summaryState), MAX_RECENT_TURNS_FULL);
        ContextTokenEstimator.Estimate estimate = estimator.estimate(layout.messages());
        if (estimate.tokens() <= budget.compressionTriggerTokens()) {
            log.info("agent_context_assembled runId={} agent={} purpose={} mode={} estimateMode={} "
                            + "estimatedInputTokens={} inputUtf8Bytes={} beforeTokens={} trimmedTokens={} droppedHistoryTurns={}",
                    request.runId(), request.agentNode(), request.purpose(), ContextMode.FULL, estimate.mode(),
                    estimate.tokens(), estimate.utf8Bytes(), estimate.tokens(), 0, 0);
            return new AssemblyResult(
                    new PreparedModelContext(layout.messages(), receipt(request, ContextMode.FULL, estimate, 0, 0, 0)),
                    Map.of());
        }
        if (estimate.tokens() <= budget.maxInputTokens()) {
            // 触发阈值与硬上限之间：可复现的确定性压缩（只动历史轮次区间，半轮不截断），压缩后必落在目标之内。
            int contextTokens = estimator.estimate(layout.messages().subList(0, layout.historyStart())).tokens();
            int allowedHistory = Math.max(budget.compressionTargetTokens() - contextTokens, 0);
            ContextDeterministicCompressor.TrimResult trimmed = compressor.trim(
                    layout.messages(), layout.historyStart(), layout.historyEnd(), allowedHistory);
            ContextTokenEstimator.Estimate afterTrim = estimator.estimate(trimmed.messages());
            log.info("agent_context_assembled runId={} agent={} purpose={} mode={} estimateMode={} "
                            + "estimatedInputTokens={} inputUtf8Bytes={} beforeTokens={} trimmedTokens={} droppedHistoryTurns={}",
                    request.runId(), request.agentNode(), request.purpose(), ContextMode.DETERMINISTIC, estimate.mode(),
                    afterTrim.tokens(), estimate.utf8Bytes(), estimate.tokens(),
                    estimate.tokens() - afterTrim.tokens(), trimmed.droppedHistoryTurns());
            return new AssemblyResult(new PreparedModelContext(trimmed.messages(),
                    receipt(request, ContextMode.DETERMINISTIC, afterTrim, estimate.tokens(), afterTrim.tokens(),
                            trimmed.droppedHistoryTurns())), Map.of());
        }
        // 超硬上限：受控 LLM 压缩兜底（仅节点入口、受调用计数限制；压缩后仅保留摘要 + 最近窗口轮次）。
        return compressOrBlock(request, summaryState, compressionCallsUsed, model, estimate, layout);
    }

    /** 完整视图保留的最近历史轮数上限（LLM 压缩后只保留摘要 + 最近窗口，设计文档 §7）。 */
    static final int MAX_RECENT_TURNS_FULL = 8;
    static final int MAX_RECENT_TURNS_COMPRESSED = 2;

    /** 消息构建（§5.1 顺序），返回历史区间供确定性压缩。 */
    private MessageLayout buildMessages(ContextAssemblyRequest request, String summaryText, int maxRecentTurns) {
        List<Message> prefix = new ArrayList<>();
        prefix.add(declarationMessage(request));
        if (summaryText != null && !summaryText.isBlank()) {
            prefix.add(new UserMessage("【会话摘要】\n" + summaryText));
        }
        List<String> refs = sortedRefs(request);
        if (!refs.isEmpty()) {
            prefix.add(new UserMessage("【稳定引用】\n" + String.join("\n", refs)));
        }
        List<Message> body = new ArrayList<>();
        List<Message> suffix = new ArrayList<>();
        int historyStart = prefix.size();
        List<ConversationContext.DialogueTurn> recentTurns = request.conversation().recentTurns();
        List<ConversationContext.DialogueTurn> window = recentTurns.size() > maxRecentTurns * 2
                ? recentTurns.subList(recentTurns.size() - maxRecentTurns * 2, recentTurns.size()) : recentTurns;
        for (ConversationContext.DialogueTurn turn : window) {
            body.add("USER".equalsIgnoreCase(turn.role()) ? new UserMessage(turn.text())
                    : new AssistantMessage(turn.text()));
        }
        int historyEnd = prefix.size() + body.size();
        String purposeBlock = purposeBlock(request);
        if (!purposeBlock.isBlank()) {
            suffix.add(new UserMessage(purposeBlock));
        }
        String guidance = pendingGuidance(request);
        if (guidance != null) {
            suffix.add(new UserMessage(guidance));
        }
        List<Message> messages = new ArrayList<>(prefix);
        messages.addAll(body);
        messages.addAll(suffix);
        return new MessageLayout(messages, historyStart, historyEnd);
    }

    /** 前缀层 1：组装声明（agentNode/purpose/定义版本，字节级稳定；定义版本与 Graph 定义版本同源）。 */
    private Message declarationMessage(ContextAssemblyRequest request) {
        return new UserMessage(String.format("【上下文】agentNode=%s | purpose=%s | 定义版本=%s",
                request.agentNode(), request.purpose(), KnowledgeCurationGraphFactory.GRAPH_DEF_VERSION));
    }

    /** 前缀层 2：按稳定 ID 排序的引用（sourceRef/draftId+revision/decisionId）。 */
    private static List<String> sortedRefs(ContextAssemblyRequest request) {
        List<String> refs = new ArrayList<>();
        WorkflowContext workflow = request.workflow();
        if (workflow != null) {
            workflow.sourceRefs().forEach(ref -> refs.add("sourceRef: " + ref.type() + ":" + ref.id()));
            workflow.drafts().forEach(draft -> refs.add("draftRef: " + draft.draftId() + " revision=" + draft.revision()));
            if (workflow.reviewTarget() != null) {
                refs.add("reviewTarget: " + workflow.reviewTarget().draftId() + " revision=" + workflow.reviewTarget().revision());
            }
        }
        request.conversation().confirmedDecisions()
                .forEach(decision -> refs.add("decisionId: " + decision.id()));
        refs.sort(String::compareTo);
        return refs;
    }

    /** 动态后缀任务块：按 purpose 组装业务语义（矩阵见设计文档 §5）。 */
    private String purposeBlock(ContextAssemblyRequest request) {
        ContextPurpose purpose = request.purpose();
        WorkflowContext workflow = request.workflow();
        if (purpose == ContextPurpose.REPAIR) {
            return repairBlock(request, workflow);
        }
        List<String> blocks = new ArrayList<>();
        blocks.add("【当前指令】" + text(request.currentInstruction()));
        if (workflow != null) {
            if (!workflow.facts().isEmpty()) {
                StringBuilder facts = new StringBuilder("【允许处理的事实与引用】");
                for (WorkflowContext.SupportedFact fact : workflow.facts()) {
                    facts.append("\n- ").append(bounded(fact.statement(), 300))
                            .append(" sourceRefs=[").append(String.join(",", fact.sourceRefs())).append("]");
                }
                blocks.add(facts.toString());
            }
            if (workflow.draftInstruction() != null) {
                blocks.add("【写入要求】目标目录=" + text(workflow.draftInstruction().targetDirectory())
                        + "；任务=" + bounded(workflow.draftInstruction().instruction(), 300));
            }
            if (workflow.reviewTarget() != null) {
                blocks.add("【审查目标】draftId=" + workflow.reviewTarget().draftId()
                        + " revision=" + workflow.reviewTarget().revision());
            }
            if (!workflow.findings().isEmpty()) {
                StringBuilder findings = new StringBuilder("【本轮审查发现】");
                for (WorkflowContext.ReviewFinding finding : workflow.findings()) {
                    findings.append("\n- ").append(bounded(finding.code(), 60))
                            .append(" draftId=").append(bounded(finding.draftId(), 60))
                            .append("：").append(bounded(finding.description(), 300));
                }
                blocks.add(findings.toString());
            }
            if (workflow.retry() != null) {
                blocks.add("【修复信息】attempt=" + workflow.retry().attempt()
                        + " lastValidatedNode=" + text(workflow.retry().lastValidatedNode())
                        + " 错误摘要=" + bounded(workflow.retry().validationError(), 300));
            }
            if (!workflow.unresolvedQuestions().isEmpty()) {
                blocks.add("【未解决问题】" + workflow.unresolvedQuestions().stream()
                        .map(q -> q.id() + " " + bounded(q.question(), 200)).sorted().toList());
            }
        }
        if (purpose == ContextPurpose.CHAT || purpose == ContextPurpose.DIRECT_RETRIEVE
                || purpose == ContextPurpose.DIRECT_DRAFT || purpose == ContextPurpose.DIRECT_REVIEW) {
            blocks.add("【任务状态摘要】原始目标=" + text(request.conversation().originalGoal())
                    + (request.conversation().historyTruncated() ? "；较早轮次已截断，以会话摘要为准。" : ""));
        } else {
            blocks.add("【任务】" + text(request.conversation().originalGoal()));
        }
        String marker = stageMarker(request);
        if (marker != null) {
            blocks.add(marker);
        }
        return String.join("\n\n", blocks);
    }

    /** 阶段标记：只对 DECIDE/FINISH/REPORT 的节点入口生成，与 Agent Spec 契约的显式阶段语义一致。 */
    private String stageMarker(ContextAssemblyRequest request) {
        return switch (request.purpose()) {
            case FULL_CURATION_DECIDE ->
                    "【当前阶段：DECIDE】\n检索已完成。请依据上方事实决定下一步：只能输出 DRAFT、ASK_USER 或 NO_CHANGE。切勿输出 CHAT 或 RETRIEVE。";
            case FULL_CURATION_FINISH ->
                    "【当前阶段：FINISH】\n所有工作已完成。你是唯一汇报口径：请输出 action=END，并在 summary 给出面向管理员的最终汇报。切勿再次输出“请提供…”“现在开始检索…”等开场白。";
            case FULL_CURATION_REPORT ->
                    "【当前阶段：FULL CURATION 完成】\n完整整理流程已完成，专家结果都已经过校验。请只输出 TURN_DONE，并在 summary 给出面向管理员的最终汇报，不要再调用任何专家、不要再发起完整整理。";
            default -> null;
        };
    }

    /** 修复块：最小输入 + 有界错误摘要 + lastValidatedNode（设计文档 §5 REPAIR 行）。 */
    private String repairBlock(ContextAssemblyRequest request, WorkflowContext workflow) {
        String retryText = workflow == null || workflow.retry() == null ? ""
                : bounded(workflow.retry().validationError(), 300);
        String lastNode = workflow == null || workflow.retry() == null || workflow.retry().lastValidatedNode() == null
                ? "未知" : workflow.retry().lastValidatedNode();
        int attempt = workflow == null || workflow.retry() == null ? 0 : workflow.retry().attempt();
        boolean drafter = request.agentNode() == io.github.loredock.agent.model.enums.AgentNode.DRAFTER;
        String reconciliation = drafter
                ? "；若上一轮你已成功写入草稿（工具回执显示新修订），请先确认当前 revision 再输出" : "";
        return "【结构化结果无效，请修复】\n你刚输出的结构化结果校验失败（lastValidatedNode=" + lastNode
                + "，attempt=" + attempt + "），错误摘要：" + retryText
                + "\n请按任务要求重新输出一份字段完整、严格符合 JSON 结构的全新结果，不要重复错误字段，不要输出解释文本。"
                + reconciliation;
    }

    private static String pendingGuidance(ContextAssemblyRequest request) {
        ConversationContext.AdministratorGuidance guidance = request.conversation().pendingAdministratorGuidance();
        if (guidance == null || guidance.targetAgent() == null) {
            return null;
        }
        return guidance.targetAgent().equals(request.agentNode().name().toLowerCase())
                ? "管理员追加指导：" + guidance.text() : null;
    }

    /** 摘要复用判定：已提交且未失效（schema 版本一致 + digest 与业务消息范围一致）才可复用。 */
    private String usableSummary(ContextAssemblyRequest request, ContextSummaryState summary) {
        if (summary == null || !summary.hasSummary()) {
            return null;
        }
        if (!ContextSummaryState.SCHEMA_VERSION.equals(summary.summarySchemaVersion())) {
            return null;
        }
        try {
            String digest = compressionService.digest(request.conversationId(), targetSkill(request),
                    summary.summaryThroughMessageId());
            if (!summary.summarySourceDigest().equals(digest)) {
                log.info("上下文摘要失效（源消息变动）conversationId={} 从业务消息表重建",
                        request.conversationId());
                return null;
            }
        } catch (Exception exception) {
            return null;
        }
        return summary.conversationSummary();
    }

    /** LLM 压缩兜底：重建或滚动摘要后以“摘要 + 最近窗口轮次”重组装；仍超限返回 BLOCKED。 */
    private AssemblyResult compressOrBlock(
            ContextAssemblyRequest request,
            ContextSummaryState summaryState,
            int compressionCallsUsed,
            ChatModel model,
            ContextTokenEstimator.Estimate estimate,
            MessageLayout layout
    ) {
        String summary = null;
        Map<String, Object> updates = new LinkedHashMap<>();
        int callsUsed = compressionCallsUsed;
        try {
            boolean reuse = usableSummary(request, summaryState) != null;
            if (reuse) {
                summary = usableSummary(request, summaryState);
            } else {
                if (budget.maxLlmCompressionCalls() == 0
                        || compressionCallsUsed >= budget.maxLlmCompressionCalls()) {
                    return blocked(request, estimate, estimator.estimate(layout.messages()).tokens());
                }
                SummaryMaterial material = buildMaterial(request, summaryState);
                ContextCompressionService.CompressionResult result = compressionService.summarize(
                        model, material.turns(), budget.compressionTargetTokens(), material.ids());
                summary = result.summary();
                callsUsed = compressionCallsUsed + 1;
                updates.put(COMPRESSION_CALLS_KEY, callsUsed);
                updates.put("conversationSummary", summary);
                updates.put("summaryThroughMessageId", material.throughMessageId());
                updates.put("summarySourceDigest",
                        compressionService.digest(request.conversationId(), targetSkill(request), material.throughMessageId()));
                updates.put("summarySchemaVersion", ContextSummaryState.SCHEMA_VERSION);
                updates.put("summaryGeneration", material.generation());
            }
        } catch (RuntimeException exception) {
            log.warn("上下文压缩兜底失败 conversationId={} runId={} error={}（转 BLOCKED，不递归压缩）",
                    request.conversationId(), request.runId(), bounded(String.valueOf(exception.getMessage()), 200));
            return blocked(request, estimate, estimator.estimate(layout.messages()).tokens());
        }
        MessageLayout rebuilt = buildMessages(request, summary, MAX_RECENT_TURNS_COMPRESSED);
        ContextTokenEstimator.Estimate after = estimator.estimate(rebuilt.messages());
        if (after.tokens() > budget.maxInputTokens()) {
            log.warn("上下文组装 LLM 压缩后仍超限 agent={} purpose={} 估算={}（转 BLOCKED）",
                    request.agentNode(), request.purpose(), after.tokens());
            return blocked(request, estimate, estimator.estimate(layout.messages()).tokens());
        }
        log.info("agent_context_assembled runId={} agent={} purpose={} mode={} estimateMode={} "
                        + "estimatedInputTokens={} inputUtf8Bytes={} beforeTokens={} trimmedTokens={}",
                request.runId(), request.agentNode(), request.purpose(), ContextMode.LLM_COMPRESSED, estimate.mode(),
                after.tokens(), estimate.utf8Bytes(), estimate.tokens(), estimate.tokens() - after.tokens());
        return new AssemblyResult(new PreparedModelContext(rebuilt.messages(),
                receipt(request, ContextMode.LLM_COMPRESSED, after, estimate.tokens(), after.tokens(), 0)), updates);
    }

    private AssemblyResult blocked(
            ContextAssemblyRequest request, ContextTokenEstimator.Estimate estimate, int estimatedAfterTrim
    ) {
        log.warn("agent_context_assembled runId={} agent={} purpose={} mode={} estimateMode={} "
                        + "estimatedInputTokens={} inputUtf8Bytes={} beforeTokens={}（转 WAITING_FOR_USER）",
                request.runId(), request.agentNode(), request.purpose(), ContextMode.BLOCKED, estimate.mode(),
                estimate.tokens(), estimate.utf8Bytes(), estimate.tokens());
        return new AssemblyResult(new PreparedModelContext(List.of(),
                receipt(request, ContextMode.BLOCKED, estimate, estimate.tokens(), estimate.tokens(), 0)), Map.of());
    }

    /** LLM 压缩输入资料：旧轮批次 + 代际策略 + ID 全集。 */
    private SummaryMaterial buildMaterial(ContextAssemblyRequest request, ContextSummaryState summaryState) {
        String targetSkill = targetSkill(request);
        boolean rolling = summaryState != null && summaryState.hasSummary()
                && ContextSummaryState.SCHEMA_VERSION.equals(summaryState.summarySchemaVersion())
                && summaryState.summaryGeneration() < budget.maxRollingSummaryGenerations();
        long from = rolling ? summaryState.summaryThroughMessageId() : 0L;
        ContextCompressionService.OldTurnBatch batch =
                compressionService.readOldTurns(request.conversationId(), targetSkill, from, MAX_COMPRESSION_TURNS);
        if (batch.turns().isEmpty()) {
            throw new IllegalStateException("没有可压缩的旧轮次");
        }
        int generation = rolling ? summaryState.summaryGeneration() + 1 : 1;
        ContextCompressionService.IdUniverse ids = universe(request);
        return new SummaryMaterial(batch.turns(), batch.throughMessageId(), generation, ids);
    }

    private record SummaryMaterial(
            List<ContextCompressionService.OldTurn> turns,
            long throughMessageId,
            int generation,
            ContextCompressionService.IdUniverse ids
    ) {
    }

    private ContextCompressionService.IdUniverse universe(ContextAssemblyRequest request) {
        List<String> decisions = request.conversation().confirmedDecisions().stream()
                .map(ConversationContext.ConfirmedDecision::id).toList();
        List<String> questions = request.workflow().unresolvedQuestions().stream()
                .map(WorkflowContext.UnresolvedQuestion::id).toList();
        List<String> refs = request.workflow().sourceRefs().stream()
                .map(ref -> ref.type() + ":" + ref.id()).toList();
        return new ContextCompressionService.IdUniverse(refs, decisions, questions);
    }

    private String targetSkill(ContextAssemblyRequest request) {
        KnowledgeTaskConversationEntity conversation = conversations.selectOne(
                Wrappers.<KnowledgeTaskConversationEntity>lambdaQuery()
                        .eq(KnowledgeTaskConversationEntity::getId, request.conversationId()));
        return conversation == null || conversation.getTargetSkill() == null
                ? "" : conversation.getTargetSkill();
    }

    private ContextReceipt receipt(
            ContextAssemblyRequest request,
            ContextMode mode,
            ContextTokenEstimator.Estimate estimate,
            int beforeTokens,
            int afterTokens,
            int droppedHistoryTurns
    ) {
        return new ContextReceipt(request.agentNode(), request.purpose(), mode, estimate.mode(),
                estimate.tokens(), estimate.utf8Bytes(), beforeTokens, Math.max(beforeTokens - afterTokens, 0),
                afterTokens, droppedHistoryTurns, 0, List.of());
    }

    private static final int MAX_COMPRESSION_TURNS = 30;

    private static String text(String value) {
        return value == null || value.isBlank() ? "（无）" : value;
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }
}
