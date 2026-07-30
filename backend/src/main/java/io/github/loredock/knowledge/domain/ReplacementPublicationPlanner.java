package io.github.loredock.knowledge.domain;

import java.util.Objects;

/**
 * 纯领域替代发布规划器。应用层负责按 UUID 稳定锁定并加载聚合与替代链，规划器只验证业务不变量并生成原子写入计划。
 */
public final class ReplacementPublicationPlanner {

    /**
     * 规划一次替代发布。任何冲突均不修改输入聚合。
     *
     * @param candidate 待发布的新草稿
     * @param oldDocument 当前已发布的旧文档
     * @param oldDocumentChain 从旧文档向更旧文档追溯的链
     * @param audit 本次替代发布审计
     * @return 同事务写入的新旧聚合
     * @throws DocumentReplacementConflictException 替代关系不满足业务规则
     */
    public ReplacementPublicationPlan plan(
            KnowledgeDocument candidate,
            KnowledgeDocument oldDocument,
            ReplacementChain oldDocumentChain,
            DocumentAudit audit
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(oldDocument, "oldDocument");
        Objects.requireNonNull(oldDocumentChain, "oldDocumentChain");
        Objects.requireNonNull(audit, "audit");
        boolean invalid = candidate.id().equals(oldDocument.id())
                || candidate.status() != DocumentStatus.DRAFT
                || oldDocument.status() != DocumentStatus.PUBLISHED
                || !candidate.fields().scope().equals(oldDocument.fields().scope())
                || candidate.replacement().replacesDocumentId() != null
                || candidate.replacement().replacedByDocumentId() != null
                || oldDocument.replacement().replacedByDocumentId() != null
                || oldDocumentChain.documentIds().isEmpty()
                || !oldDocumentChain.documentIds().getFirst().equals(oldDocument.id())
                || oldDocumentChain.documentIds().contains(candidate.id());
        if (invalid) {
            throw new DocumentReplacementConflictException();
        }
        KnowledgeDocument published = candidate.publishAsReplacement(oldDocument.id(), audit);
        KnowledgeDocument archived = oldDocument.archiveAsReplacedBy(candidate.id(), audit);
        return new ReplacementPublicationPlan(published, archived);
    }
}
