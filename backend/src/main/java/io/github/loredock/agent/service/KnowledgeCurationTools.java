package io.github.loredock.agent.service;

import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.AgentToolResult;
import io.github.loredock.agent.model.tool.KnowledgeSearchToolRequest;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 知识整理允许集。模型输入只包含业务参数，操作者、项目、会话和 run 必须来自服务端 ToolContext；
 * 候选集合刻意不包含 Shell、任意 HTTP、数据库管理、文件写入和正式发布能力。
 */
@Component
public class KnowledgeCurationTools {

    private final ProjectQaToolService knowledge;
    private final AgentEvidenceService evidence;
    private final ObjectProvider<KnowledgeDraftService> drafts;
    private final AgentRunMapper runs;
    private final KnowledgeTaskMessageMapper messages;
    private final Clock clock;

    /**
     * @param knowledge 固定运行范围的知识搜索
     * @param evidence 已提交来源
     * @param drafts 草稿业务契约
     * @param runs 运行固定范围事实
     * @param messages 冲突与缺口的公开会话记录
     * @param clock UTC 时间源
     */
    public KnowledgeCurationTools(
            ProjectQaToolService knowledge,
            AgentEvidenceService evidence,
            ObjectProvider<KnowledgeDraftService> drafts,
            AgentRunMapper runs,
            KnowledgeTaskMessageMapper messages,
            Clock clock
    ) {
        this.knowledge = knowledge;
        this.evidence = evidence;
        this.drafts = drafts;
        this.runs = runs;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * 在服务端固定范围内检索已发布知识。
     *
     * @param query 检索问题
     * @param limit 期望返回数量
     * @param context 服务端运行范围
     * @return 有限证据上下文
     */
    @Tool(name = "knowledge_search", description = "在服务端固定项目与活动索引中搜索已发布知识")
    public AgentToolResult knowledgeSearch(
            @ToolParam(description = "要检索的项目知识问题") String query,
            @ToolParam(description = "期望返回数量，最终仍受服务端上限约束") Integer limit,
            ToolContext context
    ) {
        return knowledge.knowledgeSearch(scope(context).runId(), new KnowledgeSearchToolRequest(query, limit));
    }

    /**
     * 读取当前 run 已登记证据的有限来源信息。
     *
     * @param evidenceId 证据 ID
     * @param context 服务端运行范围
     * @return 有限来源信息
     */
    @Tool(name = "evidence_read", description = "读取当前 run 已登记证据的有限来源信息")
    public EvidenceView evidenceRead(
            @ToolParam(description = "当前 run 已登记的证据 ID") Long evidenceId,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        AgentEvidence value = evidence.findByRunId(scope.runId()).stream()
                .filter(item -> item.id().equals(evidenceId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("当前 run 不存在该证据"));
        return new EvidenceView(value.id(), value.documentId(), value.title(), value.sourceUpdatedAt(),
                value.projectIdentifier(), value.branch(), value.retained());
    }

    /**
     * 按当前 run 的 evidenceId 读取已授权知识来源摘要。
     *
     * @param evidenceId 已保留证据 ID
     * @param context 服务端运行范围
     * @return 已授权来源摘要
     */
    @Tool(name = "knowledge_read", description = "按当前 run 的 evidenceId 读取已授权知识来源摘要")
    public EvidenceView knowledgeRead(
            @ToolParam(description = "当前 run 已保留的证据 ID") Long evidenceId,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return evidence.findByRunId(scope.runId()).stream()
                .filter(item -> item.id().equals(evidenceId) && item.retained())
                .findFirst()
                .map(item -> new EvidenceView(item.id(), item.documentId(), item.title(),
                        item.sourceUpdatedAt(), item.projectIdentifier(), item.branch(), true))
                .orElseThrow(() -> new IllegalArgumentException("当前 run 不存在可读取证据"));
    }

    /**
     * 创建空基线或绑定正式文档的版本化待审核草稿。
     *
     * @param idempotencyKey 调用幂等键
     * @param title 草稿标题
     * @param baselineDocumentId 可选基线文档
     * @param context 服务端运行范围
     * @return 初始草稿修订
     */
    @Tool(name = "draft_create", description = "创建空基线或绑定正式文档的版本化待审核草稿")
    public KnowledgeDraftService.DraftRevision draftCreate(
            @ToolParam(description = "本次创建调用的幂等键") String idempotencyKey,
            @ToolParam(description = "草稿标题") String title,
            @ToolParam(description = "可选的正式知识基线文档 ID") Long baselineDocumentId,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return draftService().create(new KnowledgeDraftService.CreateRequest(
                scope.access(), idempotencyKey, title, baselineDocumentId));
    }

    /**
     * 读取草稿修订、Markdown 目录与服务端稳定区块 ID。
     *
     * @param draftId 草稿 ID
     * @param revision 可选修订号
     * @param context 服务端运行范围
     * @return 草稿修订
     */
    @Tool(name = "draft_read", description = "读取草稿修订、Markdown 目录与服务端稳定区块 ID")
    public KnowledgeDraftService.DraftRevision draftRead(
            @ToolParam(description = "草稿 ID") Long draftId,
            @ToolParam(description = "可选修订号；未提供时读取最新修订") Long revision,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return draftService().read(new KnowledgeDraftService.ReadRequest(scope.access(), draftId, revision));
    }

    /**
     * 基于已读取修订原子应用有界区块操作。
     *
     * @param draftId 草稿 ID
     * @param baseRevision 基础修订号
     * @param idempotencyKey 调用幂等键
     * @param operations 有界区块操作
     * @param changeSummary 修改摘要
     * @param context 服务端运行范围
     * @return 新草稿修订
     */
    @Tool(name = "draft_update", description = "基于已读取修订原子应用有界区块操作；不支持全文覆盖")
    public KnowledgeDraftService.DraftRevision draftUpdate(
            @ToolParam(description = "草稿 ID") Long draftId,
            @ToolParam(description = "更新所基于的修订号") long baseRevision,
            @ToolParam(description = "本次更新调用的幂等键") String idempotencyKey,
            @ToolParam(description = "有界区块更新操作") List<KnowledgeDraftService.UpdateOperation> operations,
            @ToolParam(description = "本次修改摘要") String changeSummary,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        List<KnowledgeDraftService.UpdateOperation> safeOperations = operations == null
                ? List.of() : List.copyOf(operations);
        validateDraftSources(safeOperations, scope);
        return draftService().update(new KnowledgeDraftService.UpdateRequest(
                scope.access(), draftId, baseRevision, idempotencyKey, safeOperations, changeSummary));
    }

    private void validateDraftSources(List<KnowledgeDraftService.UpdateOperation> operations, ToolScope scope) {
        List<Long> evidenceIds = evidence.findByRunId(scope.runId()).stream()
                .map(AgentEvidence::id).toList();
        List<Long> userMessageIds = messages.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<KnowledgeTaskMessageEntity>lambdaQuery()
                                .eq(KnowledgeTaskMessageEntity::getConversationId, scope.conversationId())
                                .eq(KnowledgeTaskMessageEntity::getRole, "USER"))
                .stream().map(KnowledgeTaskMessageEntity::getId).toList();
        boolean invalid = operations.stream()
                .flatMap(operation -> operation.sourceRefs().stream())
                .anyMatch(source -> source.type() == KnowledgeDraftService.SourceType.EVIDENCE
                        ? !evidenceIds.contains(source.sourceId())
                        : source.type() != KnowledgeDraftService.SourceType.USER_MESSAGE
                                || !userMessageIds.contains(source.sourceId()));
        if (invalid) {
            throw new IllegalArgumentException("草稿修订引用了当前 run 或会话之外的来源");
        }
    }

    /**
     * 由服务端生成两个已提交修订之间的 Markdown unified diff。
     *
     * @param draftId 草稿 ID
     * @param fromRevision 起始修订号
     * @param toRevision 目标修订号
     * @param context 服务端运行范围
     * @return 有界 Diff
     */
    @Tool(name = "draft_diff", description = "由服务端生成两个已提交修订之间的 Markdown unified diff")
    public KnowledgeDraftService.DraftDiff draftDiff(
            @ToolParam(description = "草稿 ID") Long draftId,
            @ToolParam(description = "起始修订号") Long fromRevision,
            @ToolParam(description = "结束修订号") long toRevision,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return draftService().diff(new KnowledgeDraftService.DiffRequest(
                scope.access(), draftId, fromRevision, toRevision));
    }

    /**
     * 把有来源的冲突候选记录到当前知识任务会话。
     *
     * @param summary 冲突摘要
     * @param evidenceIds 当前 run 证据 ID
     * @param context 服务端运行范围
     * @return 已记录发现项
     */
    @Tool(name = "conflict_record", description = "把有来源的冲突候选记录到当前知识任务会话，等待管理员处理")
    public FindingView conflictRecord(
            @ToolParam(description = "冲突候选摘要") String summary,
            @ToolParam(description = "当前 run 内支持该结论的证据 ID") List<Long> evidenceIds,
            ToolContext context
    ) {
        return recordFinding("conflict_record", summary, evidenceIds, scope(context));
    }

    /**
     * 把证据不足的知识缺口记录到当前知识任务会话。
     *
     * @param summary 缺口摘要
     * @param evidenceIds 当前 run 相关证据 ID
     * @param context 服务端运行范围
     * @return 已记录发现项
     */
    @Tool(name = "knowledge_gap_record", description = "把证据不足的知识缺口记录到当前知识任务会话，等待管理员补充")
    public FindingView knowledgeGapRecord(
            @ToolParam(description = "知识缺口摘要") String summary,
            @ToolParam(description = "当前 run 内与该缺口相关的证据 ID") List<Long> evidenceIds,
            ToolContext context
    ) {
        return recordFinding("knowledge_gap_record", summary, evidenceIds, scope(context));
    }

    private FindingView recordFinding(String name, String value, List<Long> values, ToolScope scope) {
        String summary = value == null ? "" : value.strip();
        List<Long> evidenceIds = values == null ? List.of() : List.copyOf(values);
        if (summary.isEmpty() || summary.codePointCount(0, summary.length()) > 1000
                || evidenceIds.size() > 20) {
            throw new IllegalArgumentException("知识整理发现项参数无效");
        }
        List<Long> available = evidence.findByRunId(scope.runId()).stream().map(AgentEvidence::id).toList();
        if (!available.containsAll(evidenceIds)) {
            throw new IllegalArgumentException("发现项引用了当前 run 之外的证据");
        }
        KnowledgeTaskMessageEntity entity = KnowledgeTaskMessageEntity.builder()
                .conversationId(scope.conversationId()).runId(scope.runId()).role("TOOL")
                .subjectName(name).content(summary).createdAt(clock.instant()).build();
        messages.insert(entity);
        return new FindingView(entity.getId(), name, summary, evidenceIds);
    }

    private KnowledgeDraftService draftService() {
        return drafts.getIfAvailable(() -> {
            throw new IllegalStateException("知识草稿能力尚未装配");
        });
    }

    private ToolScope scope(ToolContext context) {
        if (context == null) {
            throw new IllegalArgumentException("知识 Tool 缺少服务端固定上下文");
        }
        Map<String, Object> values = context.getContext();
        ToolScope scope = new ToolScope(
                text(values, "operatorId"), text(values, "projectIdentifier"),
                number(values, "conversationId"), number(values, "runId"));
        AgentRunEntity run = runs.selectById(scope.runId());
        if (run == null || !scope.operatorId().equals(run.getOperatorId())
                || !scope.projectIdentifier().equals(run.getProjectIdentifier())
                || !scope.conversationId().equals(run.getKnowledgeTaskConversationId())
                || !"knowledge_curation".equals(run.getTaskType())
                || !"RUNNING".equals(run.getStatus())) {
            throw new IllegalArgumentException("知识 Tool 上下文与运行固定范围不一致");
        }
        return scope;
    }

    private String text(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("知识 Tool 上下文缺少 " + name);
        }
        return text;
    }

    private Long number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalArgumentException("知识 Tool 上下文缺少 " + name);
        }
        return number.longValue();
    }

    /** ToolContext 解析后的固定范围，不暴露给模型 schema。 */
    private record ToolScope(String operatorId, String projectIdentifier, Long conversationId, Long runId) {
        private KnowledgeDraftService.AccessContext access() {
            return new KnowledgeDraftService.AccessContext(operatorId, projectIdentifier, conversationId, runId);
        }
    }

    /** 证据读取只返回来源摘要，不返回隐藏提示或超限正文。 */
    public record EvidenceView(
            Long evidenceId, Long documentId, String title, java.time.Instant sourceUpdatedAt,
            String projectIdentifier, String branch, boolean retained
    ) {
    }

    /** 已写入会话的公开发现项，不包含证据正文。 */
    public record FindingView(Long messageId, String type, String summary, List<Long> evidenceIds) {
        public FindingView {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }
}
