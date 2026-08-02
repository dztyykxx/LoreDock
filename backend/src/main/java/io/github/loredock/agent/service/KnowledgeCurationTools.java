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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final KnowledgeTaskSelectedDraftMapper selectedDrafts;
    private final KnowledgeDocumentAccessService documents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @param knowledge 固定运行范围的知识搜索
     * @param evidence 已提交来源
     * @param drafts 草稿业务契约
     * @param runs 运行固定范围事实
     * @param messages 冲突与缺口的公开会话记录
     * @param selectedDrafts 当前会话固定的草稿快照
     * @param documents 已发布知识目录、全文与关键词匹配
     * @param objectMapper 结构化发现项 JSON 编码
     * @param clock UTC 时间源
     */
    public KnowledgeCurationTools(
            ProjectQaToolService knowledge,
            AgentEvidenceService evidence,
            ObjectProvider<KnowledgeDraftService> drafts,
            AgentRunMapper runs,
            KnowledgeTaskMessageMapper messages,
            KnowledgeTaskSelectedDraftMapper selectedDrafts,
            KnowledgeDocumentAccessService documents,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.knowledge = knowledge;
        this.evidence = evidence;
        this.drafts = drafts;
        this.runs = runs;
        this.messages = messages;
        this.selectedDrafts = selectedDrafts;
        this.documents = documents;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
    @Tool(name = "selected_draft_read", description = "按文档 ID 读取当前任务固定的待处理草稿 Markdown 快照")
    public SelectedDraftContent selectedDraftRead(
            @ToolParam(description = "selected_draft_list 返回的待处理草稿文档 ID") Long documentId,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return selectedDrafts(scope.conversationId()).stream()
                .filter(value -> value.getDocumentId().equals(documentId))
                .findFirst()
                .map(value -> new SelectedDraftContent(value.getDocumentId(), value.getDocumentRevision(),
                        value.getTitle(), value.getDirectoryPath(), value.getOriginalFilename(), value.getMarkdown()))
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

    /** @return 指定已发布文档的完整 Markdown */
    @Tool(name = "knowledge_document_read", description = "按文档 ID 读取当前项目授权范围内的已发布 Markdown 全文")
    public KnowledgeDocumentAccessService.DocumentContent knowledgeDocumentRead(
            @ToolParam(description = "目录、搜索或匹配结果返回的已发布文档 ID") Long documentId,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return documents.readPublished(scope.projectIdentifier(), documentId);
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

    /**
     * 保存可追溯的重复、冲突、过期或缺口发现项。
     *
     * @return 幂等写入后的结构化发现项
     */
    @Tool(name = "finding_record", description = "保存带勾选草稿和已发布知识证据的重复、冲突、过期或缺口发现项")
    public FindingView findingRecord(
            @ToolParam(description = "发现类型：DUPLICATE、CONFLICT、STALE 或 GAP") String type,
            @ToolParam(description = "发现项主题") String topic,
            @ToolParam(description = "各方结论、适用范围与差异摘要") String summary,
            @ToolParam(description = "knowledge_search 在当前 run 登记的已发布知识证据 ID") List<Long> evidenceIds,
            @ToolParam(description = "selected_draft_list 返回的相关待处理草稿文档 ID") List<Long> selectedDraftIds,
            @ToolParam(description = "可选处理建议") String recommendation,
            @ToolParam(description = "无法确定时留给管理员的问题") String humanQuestion,
            @ToolParam(description = "当前 run 内的稳定幂等键") String idempotencyKey,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        String safeType = required(type, 20);
        if (!List.of("DUPLICATE", "CONFLICT", "STALE", "GAP").contains(safeType)) {
            throw new IllegalArgumentException("知识整理发现类型无效");
        }
        String key = required(idempotencyKey, 64);
        List<Long> safeEvidence = stableIds(evidenceIds, 20);
        List<Long> safeSelected = stableIds(selectedDraftIds, 20);
        List<Long> availableEvidence = evidence.findByRunId(scope.runId()).stream().map(AgentEvidence::id).toList();
        List<Long> availableSelected = selectedDrafts(scope.conversationId()).stream()
                .map(KnowledgeTaskSelectedDraftEntity::getDocumentId).toList();
        if (!availableEvidence.containsAll(safeEvidence) || !availableSelected.containsAll(safeSelected)) {
            throw new IllegalArgumentException("发现项引用了当前任务之外的来源");
        }
        FindingPayload payload = new FindingPayload(safeType, required(topic, 255), required(summary, 2000),
                safeEvidence, safeSelected, optional(recommendation, 1000), optional(humanQuestion, 1000), "OPEN");
        String subject = "finding_record:" + key;
        String json = json(payload);
        KnowledgeTaskMessageEntity replay = messages.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<KnowledgeTaskMessageEntity>lambdaQuery()
                        .eq(KnowledgeTaskMessageEntity::getConversationId, scope.conversationId())
                        .eq(KnowledgeTaskMessageEntity::getRunId, scope.runId())
                        .eq(KnowledgeTaskMessageEntity::getRole, "TOOL")
                        .eq(KnowledgeTaskMessageEntity::getSubjectName, subject));
        if (replay != null) {
            if (!replay.getContent().equals(json)) {
                throw new IllegalArgumentException("发现项幂等键与既有请求冲突");
            }
            return new FindingView(replay.getId(), payload);
        }
        KnowledgeTaskMessageEntity entity = KnowledgeTaskMessageEntity.builder()
                .conversationId(scope.conversationId()).runId(scope.runId()).role("TOOL")
                .subjectName(subject).content(json).createdAt(clock.instant()).build();
        messages.insert(entity);
        return new FindingView(entity.getId(), payload);
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

    private List<Long> stableIds(List<Long> values, int maximum) {
        List<Long> ids = values == null ? List.of() : List.copyOf(values);
        if (ids.size() > maximum || ids.stream().anyMatch(id -> id == null || id <= 0)
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("发现项来源标识无效");
        }
        return ids;
    }

    private String required(String value, int maximum) {
        String normalized = optional(value, maximum);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("发现项文本参数不能为空");
        }
        return normalized;
    }

    private String optional(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw new IllegalArgumentException("发现项文本参数超出上限");
        }
        return normalized;
    }

    private String json(FindingPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("发现项无法编码", exception);
        }
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
            String originalFilename, String markdown
    ) { }

    /** 已写入会话的结构化发现项。 */
    public record FindingView(Long messageId, FindingPayload finding) { }

    /** 发现项 JSON 载体，供任务页和后续审核直接读取。 */
    public record FindingPayload(
            String type,
            String topic,
            String summary,
            List<Long> evidenceIds,
            List<Long> selectedDraftIds,
            String recommendation,
            String humanQuestion,
            String status
    ) {
        public FindingPayload {
            evidenceIds = List.copyOf(evidenceIds);
            selectedDraftIds = List.copyOf(selectedDraftIds);
        }
    }
}
