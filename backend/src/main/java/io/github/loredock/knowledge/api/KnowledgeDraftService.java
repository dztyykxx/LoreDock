package io.github.loredock.knowledge.api;

import java.time.Instant;
import java.util.List;

/**
 * 向 Agent 模块提供的版本化知识草稿契约。
 *
 * <p>所有写入都由服务端固定项目、会话和 run 范围，并通过基础修订、调用幂等键、结构化区块操作
 * 与来源引用进行原子校验。该契约不提供全量覆盖正式知识或绕过管理员审核的发布 Tool。</p>
 */
public interface KnowledgeDraftService {

    /**
     * 创建空基线草稿或绑定待修订正式文档。
     *
     * @param request 固定范围、标题、可选基线文档和幂等键
     * @return 初始不可变修订
     * @throws KnowledgeDraftException 范围、基线或幂等请求冲突
     */
    DraftRevision create(CreateRequest request);

    /**
     * 读取当前或指定修订及服务端区块 ID。
     *
     * @param request 固定范围、草稿和可选修订号
     * @return 不可变修订快照
     * @throws KnowledgeDraftException 草稿不可见或修订不存在
     */
    DraftRevision read(ReadRequest request);

    /**
     * 按修订号升序列出管理员可见的全部不可变修订。
     *
     * @param request 固定范围与草稿；其中修订号字段会被忽略
     * @return 至少包含初始基线的修订列表
     * @throws KnowledgeDraftException 草稿不可见
     */
    List<DraftRevision> list(ReadRequest request);

    /** @return 当前会话全部工作文档；包含空 v0 供 Agent 恢复，但页面可按 currentRevision 过滤 */
    List<WorkspaceDocument> listWorkspace(AccessContext context);

    /** @return 指定 run 对多份工作文档产生的净变化 */
    RunPatchSet patchSet(AccessContext context, Long runId);

    /**
     * 基于当前修订原子应用有界区块操作。
     *
     * @param request 基础修订、调用幂等键、操作和来源
     * @return 新修订；相同幂等键和相同输入重试时返回首次成功结果
     * @throws KnowledgeDraftException 基础修订过期、同键异参、来源无效或操作无效
     */
    DraftRevision update(UpdateRequest request);

    /**
     * 在当前修订上更正新增工作文档的标题，并以正文不变的新修订进入审核链。
     *
     * @param request 固定范围、基础修订、新标题和幂等键
     * @return 标题已更新的新修订
     * @throws KnowledgeDraftException 修改正式文档工作副本、修订过期或幂等冲突
     */
    DraftRevision rename(RenameRequest request);

    /**
     * 由服务端生成基线/旧修订到目标修订的 Markdown unified diff。
     *
     * @param request 草稿与比较修订
     * @return 有界 Diff、统计和截断状态
     * @throws KnowledgeDraftException 草稿不可见、修订不存在或比较范围无效
     */
    DraftDiff diff(DiffRequest request);

    /**
     * 发布管理员已查看的明确草稿修订。
     *
     * @param request 草稿和已审核修订
     * @return 正式文档发布结果
     * @throws KnowledgeDraftException 当前修订已变化、草稿不可见或发布状态冲突
     */
    Publication publish(PublishRequest request);

    /** 原子发布当前会话全部有变更的工作文档；任一冲突时整体回滚。 */
    WorkspacePublication publishWorkspace(WorkspacePublishRequest request);

    /**
     * 知识任务原子发布后归档原候选草稿文档，使其退出待处理草稿池。
     *
     * <p>原草稿内容已由发布吸收为正式文档或应用到基线，归档是唯一符合
     * DRAFT/PUBLISHED/ARCHIVED 生命周期的终态；已在发布前归档的草稿幂等跳过，
     * 非 DRAFT 的并发状态按发布冲突回滚整个发布事务。</p>
     *
     * @param conversationId 已发布的知识任务会话标识，仅用于审计日志
     * @param documentIds 勾选草稿对应的 knowledge_document 标识
     * @param operatorId 执行发布的操作者
     * @throws KnowledgeDraftException 草稿缺失或并发状态冲突，调用方必须回滚整个发布事务
     */
    void archiveSelectedInputs(Long conversationId, List<Long> documentIds, String operatorId);

    /**
     * @param operatorId 已认证管理员
     * @param projectIdentifier 服务端解析并固定的项目标识
     * @param conversationId 所属知识任务会话
     * @param runId 产生本次调用的 Agent run
     */
    record AccessContext(String operatorId, String projectIdentifier, Long conversationId, Long runId) {
    }

    /**
     * @param context 固定调用范围
     * @param idempotencyKey run 内稳定调用幂等键
     * @param title 待审核产物标题
     * @param baselineDocumentId 待修订正式文档；创建新文档时为空
     */
    record CreateRequest(
            AccessContext context,
            String idempotencyKey,
            String title,
            String directory,
            Long baselineDocumentId
    ) {
        public CreateRequest(AccessContext context, String idempotencyKey, String title, Long baselineDocumentId) {
            this(context, idempotencyKey, title, null, baselineDocumentId);
        }
    }

    /**
     * @param context 固定调用范围
     * @param draftId 草稿标识
     * @param revision 指定修订；为空时读取当前修订
     */
    record ReadRequest(AccessContext context, Long draftId, Long revision) {
    }

    /**
     * @param context 固定调用范围
     * @param draftId 草稿标识
     * @param baseRevision Agent 实际读取的基础修订
     * @param idempotencyKey run 内稳定调用幂等键
     * @param operations 有界且全部原子成功或失败的区块操作
     * @param changeSummary 面向审核者的有限变更摘要
     */
    record UpdateRequest(
            AccessContext context,
            Long draftId,
            long baseRevision,
            String idempotencyKey,
            List<UpdateOperation> operations,
            String changeSummary
    ) {
        public UpdateRequest {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    /** 新增工作文档改名请求；MODIFY 文档标题仍由正式基线固定。 */
    record RenameRequest(
            AccessContext context,
            Long draftId,
            long baseRevision,
            String idempotencyKey,
            String title,
            String changeSummary
    ) { }

    /**
     * @param type 插入、替换或删除
     * @param targetBlockId 服务端返回的目标区块；空草稿首次插入时可为空
     * @param markdown 插入/替换内容；删除时为空
     * @param sourceRefs 本操作使用的证据或当前会话用户消息
     */
    record UpdateOperation(
            OperationType type,
            String targetBlockId,
            String markdown,
            List<SourceRef> sourceRefs
    ) {
        public UpdateOperation {
            targetBlockId = targetBlockId == null || targetBlockId.isBlank() ? null : targetBlockId;
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }

    /**
     * @param type 来源类型
     * @param sourceId 当前 run 证据或当前会话用户消息标识
     */
    record SourceRef(SourceType type, Long sourceId) {
    }

    /**
     * @param context 固定调用范围
     * @param draftId 草稿标识
     * @param fromRevision 起始草稿修订；为空表示正式文档或空基线
     * @param toRevision 目标草稿修订
     */
    record DiffRequest(AccessContext context, Long draftId, Long fromRevision, long toRevision) {
    }

    /**
     * @param context 已认证管理员范围；发布操作不会暴露为 Agent Tool
     * @param draftId 草稿标识
     * @param reviewedRevision 管理员已查看 Diff 的明确修订
     */
    record PublishRequest(AccessContext context, Long draftId, long reviewedRevision) {
    }

    record WorkspacePublishRequest(AccessContext context, List<ReviewedDraft> reviewedDrafts) {
        public WorkspacePublishRequest {
            reviewedDrafts = reviewedDrafts == null ? List.of() : List.copyOf(reviewedDrafts);
        }
    }

    record ReviewedDraft(Long draftId, long reviewedRevision) { }

    /**
     * @param draftId 草稿标识
     * @param revision 单调递增修订号，初始空/正式基线为 0
     * @param baselineDocumentId 待修订正式文档；新文档草稿为空
     * @param title 草稿标题
     * @param markdown 本修订完整 Markdown
     * @param blocks 由服务端解析并分配稳定 ID 的区块
     * @param sources 本修订实际使用的去重来源
     * @param changeSummary 变更摘要
     * @param createdByRunId 产生该修订的 run；基线修订可为空
     * @param createdAt 修订提交时间
     */
    record DraftRevision(
            Long draftId,
            long revision,
            WorkspaceOperation operation,
            Long baselineDocumentId,
            Long baselineRevision,
            String title,
            String directory,
            String markdown,
            List<DraftBlock> blocks,
            List<SourceRef> sources,
            String changeSummary,
            Long createdByRunId,
            Instant createdAt
    ) {
        public DraftRevision {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /** 工作区文档摘要，不返回正文。 */
    record WorkspaceDocument(
            Long draftId,
            WorkspaceOperation operation,
            Long baselineDocumentId,
            Long baselineRevision,
            String title,
            String directory,
            long currentRevision,
            Long lastChangedRunId
    ) { }

    /** 一轮对单份工作文档的起止修订和净变化。 */
    record RunDocumentChange(
            Long draftId,
            WorkspaceOperation operation,
            String title,
            long fromRevision,
            long toRevision,
            int additions,
            int deletions
    ) { }

    /** 一轮可见 Patch Set；不单独持久化。 */
    record RunPatchSet(Long runId, List<RunDocumentChange> documents, int additions, int deletions) {
        public RunPatchSet {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    /**
     * @param blockId 服务端稳定区块 ID
     * @param markdown 当前区块 Markdown
     */
    record DraftBlock(String blockId, String markdown) {
    }

    /**
     * @param draftId 草稿标识
     * @param fromRevision 起始草稿修订；为空表示正式文档或空基线
     * @param toRevision 目标修订
     * @param unifiedDiff 服务端生成的有界 Markdown unified diff
     * @param additions 新增行数
     * @param deletions 删除行数
     * @param truncated 是否因展示上限截断
     */
    record DraftDiff(
            Long draftId,
            Long fromRevision,
            long toRevision,
            String unifiedDiff,
            int additions,
            int deletions,
            boolean truncated
    ) {
    }

    /**
     * @param draftId 已发布草稿
     * @param revision 实际发布的已审核修订
     * @param documentId 正式知识文档标识
     * @param publishedAt 发布时间
     */
    record Publication(Long draftId, long revision, Long documentId, Instant publishedAt) {
    }

    record WorkspacePublication(List<Publication> documents, Instant publishedAt) {
        public WorkspacePublication {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    /** 允许 Agent 使用的结构化 Markdown 区块操作。 */
    enum OperationType { INSERT_AFTER, REPLACE_BLOCK, DELETE_BLOCK }

    /** 工作文档相对正式知识的操作。 */
    enum WorkspaceOperation { ADD, MODIFY }

    /** 草稿来源只允许当前 run 证据、当前会话用户消息或固定输入草稿。 */
    enum SourceType { EVIDENCE, USER_MESSAGE, SELECTED_DRAFT }
}
