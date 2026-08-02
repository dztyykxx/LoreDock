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

    /**
     * 基于当前修订原子应用有界区块操作。
     *
     * @param request 基础修订、调用幂等键、操作和来源
     * @return 新修订；相同幂等键和相同输入重试时返回首次成功结果
     * @throws KnowledgeDraftException 基础修订过期、同键异参、来源无效或操作无效
     */
    DraftRevision update(UpdateRequest request);

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
            Long baselineDocumentId
    ) {
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
            Long baselineDocumentId,
            String title,
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

    /** 允许 Agent 使用的结构化 Markdown 区块操作。 */
    enum OperationType { INSERT_AFTER, REPLACE_BLOCK, DELETE_BLOCK }

    /** 草稿来源只允许当前 run 证据或当前会话用户消息。 */
    enum SourceType { EVIDENCE, USER_MESSAGE }
}
