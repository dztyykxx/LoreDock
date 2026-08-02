package io.github.loredock.agent.service;

import io.github.loredock.agent.model.result.AgentEvidence;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 知识整理允许集。模型输入只包含业务参数，操作者、项目、会话和 run 必须来自服务端 ToolContext；
 * 候选集合刻意不包含 Shell、任意 HTTP、数据库管理、文件写入和正式发布能力。
 */
@Component
public class KnowledgeCurationToolCallbacks {

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
    public KnowledgeCurationToolCallbacks(
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

    /** @return 排序稳定且显式有限的业务 ToolCallbacks */
    public List<ToolCallback> callbacks() {
        return List.of(
                conflictRecord(), draftCreate(), draftDiff(), draftRead(), draftUpdate(), evidenceRead(),
                knowledgeGapRecord(), knowledgeRead(), knowledgeSearch());
    }

    private ToolCallback knowledgeSearch() {
        return FunctionToolCallback.builder("knowledge_search",
                        (KnowledgeSearchToolRequest input, ToolContext context) ->
                                knowledge.knowledgeSearch(scope(context).runId(), input))
                .description("在服务端固定项目与活动索引中搜索已发布知识")
                .inputType(KnowledgeSearchToolRequest.class).build();
    }

    private ToolCallback evidenceRead() {
        return FunctionToolCallback.builder("evidence_read", (EvidenceReadInput input, ToolContext context) -> {
                    ToolScope scope = scope(context);
                    AgentEvidence value = evidence.findByRunId(scope.runId()).stream()
                            .filter(item -> item.id().equals(input.evidenceId()))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException("当前 run 不存在该证据"));
                    return new EvidenceView(value.id(), value.documentId(), value.title(), value.sourceUpdatedAt(),
                            value.projectIdentifier(), value.branch(), value.retained());
                }).description("读取当前 run 已登记证据的有限来源信息")
                .inputType(EvidenceReadInput.class).build();
    }

    private ToolCallback knowledgeRead() {
        return FunctionToolCallback.builder("knowledge_read", (EvidenceReadInput input, ToolContext context) -> {
                    ToolScope scope = scope(context);
                    return evidence.findByRunId(scope.runId()).stream()
                            .filter(item -> item.id().equals(input.evidenceId()) && item.retained())
                            .findFirst()
                            .map(item -> new EvidenceView(item.id(), item.documentId(), item.title(),
                                    item.sourceUpdatedAt(), item.projectIdentifier(), item.branch(), true))
                            .orElseThrow(() -> new IllegalArgumentException("当前 run 不存在可读取证据"));
                }).description("按当前 run 的 evidenceId 读取已授权知识来源摘要")
                .inputType(EvidenceReadInput.class).build();
    }

    private ToolCallback draftCreate() {
        return FunctionToolCallback.builder("draft_create", (DraftCreateInput input, ToolContext context) -> {
                    ToolScope scope = scope(context);
                    return draftService().create(new KnowledgeDraftService.CreateRequest(
                            scope.access(), input.idempotencyKey(), input.title(), input.baselineDocumentId()));
                }).description("创建空基线或绑定正式文档的版本化待审核草稿")
                .inputType(DraftCreateInput.class).build();
    }

    private ToolCallback draftRead() {
        return FunctionToolCallback.builder("draft_read", (DraftReadInput input, ToolContext context) -> {
                    ToolScope scope = scope(context);
                    return draftService().read(new KnowledgeDraftService.ReadRequest(
                            scope.access(), input.draftId(), input.revision()));
                }).description("读取草稿修订、Markdown 目录与服务端稳定区块 ID")
                .inputType(DraftReadInput.class).build();
    }

    private ToolCallback draftUpdate() {
        return FunctionToolCallback.builder("draft_update", (DraftUpdateInput input, ToolContext context) -> {
                    ToolScope scope = scope(context);
                    validateDraftSources(input, scope);
                    return draftService().update(new KnowledgeDraftService.UpdateRequest(
                            scope.access(), input.draftId(), input.baseRevision(), input.idempotencyKey(),
                            input.operations(), input.changeSummary()));
                }).description("基于已读取修订原子应用有界区块操作；不支持全文覆盖")
                .inputType(DraftUpdateInput.class).build();
    }

    private void validateDraftSources(DraftUpdateInput input, ToolScope scope) {
        if (input == null) {
            throw new IllegalArgumentException("草稿更新参数无效");
        }
        List<Long> evidenceIds = evidence.findByRunId(scope.runId()).stream()
                .map(AgentEvidence::id).toList();
        List<Long> userMessageIds = messages.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<KnowledgeTaskMessageEntity>lambdaQuery()
                                .eq(KnowledgeTaskMessageEntity::getConversationId, scope.conversationId())
                                .eq(KnowledgeTaskMessageEntity::getRole, "USER"))
                .stream().map(KnowledgeTaskMessageEntity::getId).toList();
        boolean invalid = input.operations().stream()
                .flatMap(operation -> operation.sourceRefs().stream())
                .anyMatch(source -> source.type() == KnowledgeDraftService.SourceType.EVIDENCE
                        ? !evidenceIds.contains(source.sourceId())
                        : source.type() != KnowledgeDraftService.SourceType.USER_MESSAGE
                                || !userMessageIds.contains(source.sourceId()));
        if (invalid) {
            throw new IllegalArgumentException("草稿修订引用了当前 run 或会话之外的来源");
        }
    }

    private ToolCallback draftDiff() {
        return FunctionToolCallback.builder("draft_diff", (DraftDiffInput input, ToolContext context) -> {
                    ToolScope scope = scope(context);
                    return draftService().diff(new KnowledgeDraftService.DiffRequest(
                            scope.access(), input.draftId(), input.fromRevision(), input.toRevision()));
                }).description("由服务端生成两个已提交修订之间的 Markdown unified diff")
                .inputType(DraftDiffInput.class).build();
    }

    private ToolCallback conflictRecord() {
        return FunctionToolCallback.builder("conflict_record", (FindingInput input, ToolContext context) ->
                        recordFinding("conflict_record", input, scope(context)))
                .description("把有来源的冲突候选记录到当前知识任务会话，等待管理员处理")
                .inputType(FindingInput.class).build();
    }

    private ToolCallback knowledgeGapRecord() {
        return FunctionToolCallback.builder("knowledge_gap_record", (FindingInput input, ToolContext context) ->
                        recordFinding("knowledge_gap_record", input, scope(context)))
                .description("把证据不足的知识缺口记录到当前知识任务会话，等待管理员补充")
                .inputType(FindingInput.class).build();
    }

    private FindingView recordFinding(String name, FindingInput input, ToolScope scope) {
        String summary = input == null || input.summary() == null ? "" : input.summary().strip();
        if (summary.isEmpty() || summary.codePointCount(0, summary.length()) > 1000
                || input.evidenceIds() == null || input.evidenceIds().size() > 20) {
            throw new IllegalArgumentException("知识整理发现项参数无效");
        }
        List<Long> available = evidence.findByRunId(scope.runId()).stream().map(AgentEvidence::id).toList();
        if (!available.containsAll(input.evidenceIds())) {
            throw new IllegalArgumentException("发现项引用了当前 run 之外的证据");
        }
        KnowledgeTaskMessageEntity entity = KnowledgeTaskMessageEntity.builder()
                .conversationId(scope.conversationId()).runId(scope.runId()).role("TOOL")
                .subjectName(name).content(summary).createdAt(clock.instant()).build();
        messages.insert(entity);
        return new FindingView(entity.getId(), name, summary, input.evidenceIds());
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

    /** @param evidenceId 当前 run 已登记证据 ID */
    public record EvidenceReadInput(Long evidenceId) {
    }

    /** 证据读取只返回来源摘要，不返回隐藏提示或超限正文。 */
    public record EvidenceView(
            Long evidenceId, Long documentId, String title, java.time.Instant sourceUpdatedAt,
            String projectIdentifier, String branch, boolean retained
    ) {
    }

    /** 草稿创建的模型可控参数。 */
    public record DraftCreateInput(String idempotencyKey, String title, Long baselineDocumentId) {
    }

    /** 草稿读取的模型可控参数。 */
    public record DraftReadInput(Long draftId, Long revision) {
    }

    /** 草稿更新的模型可控参数；身份与项目范围不在其中。 */
    public record DraftUpdateInput(
            Long draftId,
            long baseRevision,
            String idempotencyKey,
            List<KnowledgeDraftService.UpdateOperation> operations,
            String changeSummary
    ) {
        public DraftUpdateInput {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    /** 服务端 Diff 的模型可控修订参数。 */
    public record DraftDiffInput(Long draftId, Long fromRevision, long toRevision) {
    }

    /** 冲突或缺口的模型可控摘要与当前 run 证据引用。 */
    public record FindingInput(String summary, List<Long> evidenceIds) {
        public FindingInput {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /** 已写入会话的公开发现项，不包含证据正文。 */
    public record FindingView(Long messageId, String type, String summary, List<Long> evidenceIds) {
        public FindingView {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }
}
