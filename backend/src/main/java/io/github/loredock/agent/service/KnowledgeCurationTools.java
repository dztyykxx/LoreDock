package io.github.loredock.agent.service;

import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.AgentToolResult;
import io.github.loredock.agent.model.tool.KnowledgeSearchToolRequest;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskSelectedDraftMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskSelectedDraftEntity;
import io.github.loredock.knowledge.api.KnowledgeDocumentAccessService;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import java.time.Instant;
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

    private static final int DEFAULT_READ_CODE_POINTS = 8_000;
    private static final int MAX_READ_CODE_POINTS = 12_000;

    private final ProjectQaToolService knowledge;
    private final AgentEvidenceService evidence;
    private final ObjectProvider<KnowledgeDraftService> drafts;
    private final AgentRunMapper runs;
    private final KnowledgeTaskMessageMapper messages;
    private final KnowledgeTaskSelectedDraftMapper selectedDrafts;
    private final KnowledgeDocumentAccessService documents;

    /**
     * @param knowledge 固定运行范围的知识搜索
     * @param evidence 已提交来源
     * @param drafts 草稿业务契约
     * @param runs 运行固定范围事实
     * @param messages 模型主动发送的公开会话记录
     * @param selectedDrafts 当前会话固定的草稿快照
     * @param documents 已发布知识目录、全文与关键词匹配
     */
    public KnowledgeCurationTools(
            ProjectQaToolService knowledge,
            AgentEvidenceService evidence,
            ObjectProvider<KnowledgeDraftService> drafts,
            AgentRunMapper runs,
            KnowledgeTaskMessageMapper messages,
            KnowledgeTaskSelectedDraftMapper selectedDrafts,
            KnowledgeDocumentAccessService documents
    ) {
        this.knowledge = knowledge;
        this.evidence = evidence;
        this.drafts = drafts;
        this.runs = runs;
        this.messages = messages;
        this.selectedDrafts = selectedDrafts;
        this.documents = documents;
    }

    /** @return 当前会话启动时固定的待处理草稿摘要 */
    @Tool(name = "selected_draft_list", description = "列出启动本次合并任务时固定的待处理草稿")
    public List<SelectedDraftView> selectedDraftList(ToolContext context) {
        ToolScope scope = scope(context);
        return selectedDrafts(scope.conversationId()).stream()
                .map(value -> new SelectedDraftView(value.getDocumentId(), value.getDocumentRevision(),
                        value.getTitle(), value.getDirectoryPath(), value.getOriginalFilename(),
                        value.getMarkdown().codePointCount(0, value.getMarkdown().length())))
                .toList();
    }

    /** @return 指定勾选草稿在会话启动时的不可变 Markdown 快照 */
    @Tool(name = "selected_draft_read", description = "按 Unicode 码点游标分段读取当前任务固定的待处理草稿 Markdown 快照；nextCursor 为空时已到文末")
    public SelectedDraftContent selectedDraftRead(
            @ToolParam(description = "selected_draft_list 返回的待处理草稿文档 ID") Long documentId,
            @ToolParam(required = false, description = "起始 Unicode 码点游标；首段传 0，后续逐字复制 nextCursor") Integer cursor,
            @ToolParam(required = false, description = "本次最大返回码点数；建议 8000，最大 12000") Integer maxCodePoints,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return selectedDrafts(scope.conversationId()).stream()
                .filter(value -> value.getDocumentId().equals(documentId))
                .findFirst()
                .map(value -> selectedDraftPage(value, cursor, maxCodePoints))
                .orElseThrow(() -> new IllegalArgumentException("当前任务未勾选该草稿"));
    }

    /** @return 当前项目和通用范围内的已发布知识目录 */
    @Tool(name = "knowledge_directory_list", description = "列出当前项目和通用范围内的已发布知识目录")
    public List<KnowledgeDocumentAccessService.DirectoryEntry> knowledgeDirectoryList(
            @ToolParam(description = "可选目录前缀；空字符串表示全部") String prefix,
            @ToolParam(description = "期望返回数量，最终受服务端上限约束") Integer limit,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return documents.listPublishedDirectories(scope.projectIdentifier(), prefix, defaultLimit(limit));
    }

    /** @return 指定目录的已发布文档摘要 */
    @Tool(name = "knowledge_document_list", description = "列出当前项目指定目录中的已发布文档")
    public List<KnowledgeDocumentAccessService.DocumentSummary> knowledgeDocumentList(
            @ToolParam(description = "可选目录；空字符串表示全部") String directory,
            @ToolParam(description = "期望返回数量，最终受服务端上限约束") Integer limit,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return documents.listPublishedDocuments(scope.projectIdentifier(), directory, defaultLimit(limit));
    }

    /** @return 指定已发布文档的有界 Markdown 分段 */
    @Tool(name = "knowledge_document_read", description = "按 Unicode 码点游标分段读取当前项目授权范围内的已发布 Markdown；nextCursor 为空时已到文末")
    public KnowledgeDocumentAccessService.DocumentPage knowledgeDocumentRead(
            @ToolParam(description = "目录、搜索或匹配结果返回的已发布文档 ID") Long documentId,
            @ToolParam(required = false, description = "起始 Unicode 码点游标；首段传 0，后续逐字复制 nextCursor") Integer cursor,
            @ToolParam(required = false, description = "本次最大返回码点数；建议 8000，最大 12000") Integer maxCodePoints,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return documents.readPublishedPage(scope.projectIdentifier(), documentId, cursor, maxCodePoints);
    }

    /** @return 已发布 Markdown 中的有界关键词命中 */
    @Tool(name = "knowledge_grep", description = "在当前项目已发布 Markdown 中按关键词匹配行和有限上下文")
    public List<KnowledgeDocumentAccessService.KeywordMatch> knowledgeGrep(
            @ToolParam(description = "要精确核对的关键词或短语") String keyword,
            @ToolParam(description = "可选目录范围") String directory,
            @ToolParam(description = "可选文档 ID 范围；空列表表示不限文档") List<Long> documentIds,
            @ToolParam(description = "期望返回命中数量") Integer limit,
            @ToolParam(description = "命中行前后的上下文行数，最大 3") Integer contextLines,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return documents.grepPublished(scope.projectIdentifier(), keyword, directory, documentIds,
                defaultLimit(limit), contextLines == null ? 1 : contextLines);
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
     * 列出当前会话的全部工作文档，供每个新 run 从服务端事实恢复。
     */
    @Tool(name = "workspace_document_list", description = "列出当前知识任务全部工作文档及其最新修订；每轮开始时必须先调用")
    public List<KnowledgeDraftService.WorkspaceDocument> workspaceDocumentList(ToolContext context) {
        ToolScope scope = scope(context);
        return draftService().listWorkspace(scope.access());
    }

    /**
     * 创建空基线或绑定正式文档的版本化待审核草稿。
     *
     * @param idempotencyKey 调用幂等键
     * @param title 草稿标题
     * @param baselineDocumentId 可选基线文档
     * @param context 服务端运行范围
     * @return 不包含正文的创建回执
     */
    @Tool(name = "draft_create", description = "创建空基线或绑定正式文档的版本化待审核草稿；没有正式基线时省略 baselineDocumentId 或传 0；返回回执不含正文")
    public DraftWriteResult draftCreate(
            @ToolParam(description = "本次创建调用的幂等键") String idempotencyKey,
            @ToolParam(description = "草稿标题") String title,
            @ToolParam(description = "新增文档使用的已有知识目录；修改正式文档时传空字符串") String directory,
            @ToolParam(description = "可选的已发布正式知识文档 ID；新主题传 0，不能传待处理草稿 ID") Long baselineDocumentId,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        // 部分模型会用 0 表达可选 ID 为空；只在 Agent Tool 边界归一化，不放宽草稿核心契约。
        Long normalizedBaselineId = baselineDocumentId != null && baselineDocumentId == 0
                ? null : baselineDocumentId;
        return new DraftWriteResult(draftService().create(new KnowledgeDraftService.CreateRequest(
                scope.access(), idempotencyKey, title, directory, normalizedBaselineId)));
    }

    /** 兼容既有 Java 调用；模型 schema 使用带目录的 Tool 方法。 */
    public DraftWriteResult draftCreate(
            String idempotencyKey, String title, Long baselineDocumentId, ToolContext context
    ) {
        return draftCreate(idempotencyKey, title, "", baselineDocumentId, context);
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
     * @return 不包含正文的更新回执，保留新分配的稳定区块 ID
     */
    @Tool(name = "draft_update", description = "基于已读取修订原子应用可直接发布的知识区块；禁止把待确认问题、警告或执行过程写入文档；空草稿首次写入必须用 INSERT_AFTER 且 targetBlockId=null（不要传空字符串）；其他操作逐字复制 draft_read 返回的区块 ID；不支持全文覆盖；回执不含正文，需要正文时调用 draft_read")
    public DraftWriteResult draftUpdate(
            @ToolParam(description = "草稿 ID") Long draftId,
            @ToolParam(description = "更新所基于的修订号") long baseRevision,
            @ToolParam(description = "本次更新调用的幂等键") String idempotencyKey,
            @ToolParam(description = "只包含有依据且可直接发布知识的有界区块操作；问题和警告应输出到对话") List<KnowledgeDraftService.UpdateOperation> operations,
            @ToolParam(description = "本次修改摘要") String changeSummary,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        List<KnowledgeDraftService.UpdateOperation> safeOperations = operations == null
                ? List.of() : List.copyOf(operations);
        validateDraftSources(safeOperations, scope);
        return new DraftWriteResult(draftService().update(new KnowledgeDraftService.UpdateRequest(
                scope.access(), draftId, baseRevision, idempotencyKey, safeOperations, changeSummary)));
    }

    /** @return 正文不变且标题已更正、不包含正文的改名回执 */
    @Tool(name = "draft_rename", description = "更正 ADD 工作文档标题并生成正文不变的新修订；MODIFY 标题由正式基线固定；返回回执不含正文")
    public DraftWriteResult draftRename(
            @ToolParam(description = "新增工作文档 ID") Long draftId,
            @ToolParam(description = "改名所基于的当前修订号") long baseRevision,
            @ToolParam(description = "本次改名调用的幂等键") String idempotencyKey,
            @ToolParam(description = "更正后的文档标题") String title,
            @ToolParam(description = "改名原因摘要") String changeSummary,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return new DraftWriteResult(draftService().rename(new KnowledgeDraftService.RenameRequest(
                scope.access(), draftId, baseRevision, idempotencyKey, title, changeSummary)));
    }

    private void validateDraftSources(List<KnowledgeDraftService.UpdateOperation> operations, ToolScope scope) {
        List<Long> evidenceIds = evidence.findByRunId(scope.runId()).stream()
                .map(AgentEvidence::id).toList();
        List<Long> userMessageIds = messages.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<KnowledgeTaskMessageEntity>lambdaQuery()
                                .eq(KnowledgeTaskMessageEntity::getConversationId, scope.conversationId())
                                .eq(KnowledgeTaskMessageEntity::getRole, "USER"))
                .stream().map(KnowledgeTaskMessageEntity::getId).toList();
        List<Long> selectedDocumentIds = selectedDrafts(scope.conversationId()).stream()
                .map(KnowledgeTaskSelectedDraftEntity::getDocumentId).toList();
        boolean invalid = operations.stream()
                .flatMap(operation -> operation.sourceRefs().stream())
                .anyMatch(source -> switch (source.type()) {
                    case EVIDENCE -> !evidenceIds.contains(source.sourceId());
                    case USER_MESSAGE -> !userMessageIds.contains(source.sourceId());
                    case SELECTED_DRAFT -> !selectedDocumentIds.contains(source.sourceId());
                });
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

    private KnowledgeDraftService draftService() {
        return drafts.getIfAvailable(() -> {
            throw new IllegalStateException("知识草稿能力尚未装配");
        });
    }

    private List<KnowledgeTaskSelectedDraftEntity> selectedDrafts(Long conversationId) {
        return selectedDrafts.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<KnowledgeTaskSelectedDraftEntity>lambdaQuery()
                        .eq(KnowledgeTaskSelectedDraftEntity::getConversationId, conversationId)
                        .orderByAsc(KnowledgeTaskSelectedDraftEntity::getOrdinal));
    }

    private int defaultLimit(Integer value) {
        return value == null ? 20 : value;
    }

    private SelectedDraftContent selectedDraftPage(
            KnowledgeTaskSelectedDraftEntity value,
            Integer requestedCursor,
            Integer requestedMaximum
    ) {
        String markdown = value.getMarkdown();
        int total = markdown.codePointCount(0, markdown.length());
        int cursor = requestedCursor == null ? 0 : requestedCursor;
        int maximum = requestedMaximum == null ? DEFAULT_READ_CODE_POINTS : requestedMaximum;
        if (cursor < 0 || cursor > total || maximum <= 0 || maximum > MAX_READ_CODE_POINTS) {
            throw new IllegalArgumentException("草稿分段参数无效");
        }
        int end = Math.min(total, cursor + maximum);
        String page = markdown.substring(
                markdown.offsetByCodePoints(0, cursor), markdown.offsetByCodePoints(0, end));
        return new SelectedDraftContent(
                value.getDocumentId(), value.getDocumentRevision(), value.getTitle(), value.getDirectoryPath(),
                value.getOriginalFilename(), page, cursor, end < total ? end : null, total, end < total);
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

    /** 固定勾选草稿摘要。 */
    public record SelectedDraftView(
            Long documentId, long revision, String title, String directory,
            String originalFilename, int markdownCodePoints
    ) { }

    /** 固定勾选草稿全文快照。 */
    public record SelectedDraftContent(
            Long documentId, long revision, String title, String directory,
            String originalFilename, String markdown, int cursor, Integer nextCursor,
            int totalCodePoints, boolean truncated
    ) { }

    /**
     * 写类工具（创建/更新/改名）的轻量回执。只包含修订标识、稳定区块 ID 与元数据，
     * 不包含正文，避免把模型刚写入的内容原样回传占用 Agent 上下文；
     * 模型需要正文时必须显式调用 draft_read，不能依赖写结果回读。
     */
    public record DraftWriteResult(
            Long draftId,
            long revision,
            KnowledgeDraftService.WorkspaceOperation operation,
            String title,
            String directory,
            List<String> blockIds,
            String changeSummary,
            Instant createdAt
    ) {
        private DraftWriteResult(KnowledgeDraftService.DraftRevision value) {
            this(value.draftId(), value.revision(), value.operation(), value.title(), value.directory(),
                    value.blocks().stream().map(KnowledgeDraftService.DraftBlock::blockId).toList(),
                    value.changeSummary(), value.createdAt());
        }
    }

}
