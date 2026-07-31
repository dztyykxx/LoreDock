package io.github.loredock.knowledge.model;

import io.github.loredock.knowledge.exception.DocumentReplacementConflictException;
import io.github.loredock.knowledge.exception.DocumentStateConflictException;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * 不可变知识文档聚合，集中保护可编辑字段、生命周期、修订、替代追溯和审计不变量。
 * revision 只用于并发与索引同步；本聚合不保存正文历史，也不会自动触发索引。
 *
 * @param id 文档 Long
 * @param fields 当前可编辑字段
 * @param status 生命周期状态
 * @param revision 当前修订号
 * @param replacement 双向替代追溯
 * @param publishedAt 首次发布时间
 * @param publishedBy 首次发布人
 * @param archivedAt 归档时间
 * @param archivedBy 归档人
 * @param createdAt 创建时间
 * @param updatedAt 最近有效变更时间
 * @param createdBy 创建人
 * @param updatedBy 最近有效变更人
 */
public record KnowledgeDocument(
        Long id,
        KnowledgeDocumentFields fields,
        DocumentStatus status,
        DocumentRevision revision,
        ReplacementLink replacement,
        Instant publishedAt,
        String publishedBy,
        Instant archivedAt,
        String archivedBy,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {

    public KnowledgeDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        createdBy = requiredActor(createdBy, "createdBy");
        updatedBy = requiredActor(updatedBy, "updatedBy");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("document update precedes creation");
        }
        validateLifecycleAudit(status, publishedAt, publishedBy, archivedAt, archivedBy);
    }

    /**
     * 创建 revision 1 的草稿；创建和更新审计来自同一可信输入。
     *
     * @param id 服务端生成的文档 Long
     * @param fields 已校验字段
     * @param audit 创建审计
     * @return 新草稿
     */
    public static KnowledgeDocument create(Long id, KnowledgeDocumentFields fields, DocumentAudit audit) {
        Objects.requireNonNull(audit, "audit");
        return new KnowledgeDocument(
                id, fields, DocumentStatus.DRAFT, new DocumentRevision(1), ReplacementLink.none(),
                null, null, null, null,
                audit.at(), audit.at(), audit.actor(), audit.actor()
        );
    }

    /**
     * 从持久化状态恢复聚合并重新校验全部生命周期不变量。
     *
     * @return 恢复后的不可变文档
     */
    public static KnowledgeDocument restore(
            Long id,
            KnowledgeDocumentFields fields,
            DocumentStatus status,
            DocumentRevision revision,
            ReplacementLink replacement,
            Instant publishedAt,
            String publishedBy,
            Instant archivedAt,
            String archivedBy,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new KnowledgeDocument(
                id, fields, status, revision, replacement,
                publishedAt, publishedBy, archivedAt, archivedBy,
                createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * 全量替换未归档文档字段；同值请求返回当前实例，不制造修订或审计变化。
     *
     * @param changedFields 已校验的新字段
     * @param audit 有效变化的更新审计
     * @return 当前或新修订聚合
     * @throws DocumentStateConflictException 文档已归档
     */
    public KnowledgeDocument edit(KnowledgeDocumentFields changedFields, DocumentAudit audit) {
        ensureNotArchived();
        Objects.requireNonNull(changedFields, "changedFields");
        Objects.requireNonNull(audit, "audit");
        if (fields.equals(changedFields)) {
            return this;
        }
        return copy(
                changedFields, status, revision.next(), replacement,
                publishedAt, publishedBy, archivedAt, archivedBy, audit
        );
    }

    /**
     * 首次发布草稿；已经发布时幂等返回当前实例，归档终态拒绝恢复。
     *
     * @param audit 发布审计
     * @return 当前或已发布聚合
     * @throws DocumentStateConflictException 文档已归档
     */
    public KnowledgeDocument publish(DocumentAudit audit) {
        if (status == DocumentStatus.ARCHIVED) {
            throw new DocumentStateConflictException();
        }
        Objects.requireNonNull(audit, "audit");
        if (status == DocumentStatus.PUBLISHED) {
            return this;
        }
        return copy(
                fields, DocumentStatus.PUBLISHED, revision.next(), replacement,
                audit.at(), audit.actor(), null, null, audit
        );
    }

    /**
     * 归档草稿或已发布文档；已归档时幂等返回当前实例且不覆盖首次归档审计。
     *
     * @param audit 归档审计
     * @return 当前或已归档聚合
     */
    public KnowledgeDocument archive(DocumentAudit audit) {
        Objects.requireNonNull(audit, "audit");
        if (status == DocumentStatus.ARCHIVED) {
            return this;
        }
        return copy(
                fields, DocumentStatus.ARCHIVED, revision.next(), replacement,
                publishedAt, publishedBy, audit.at(), audit.actor(), audit
        );
    }

    KnowledgeDocument publishAsReplacement(Long oldDocumentId, DocumentAudit audit) {
        Objects.requireNonNull(oldDocumentId, "oldDocumentId");
        Objects.requireNonNull(audit, "audit");
        if (status != DocumentStatus.DRAFT || replacement.replacesDocumentId() != null
                || replacement.replacedByDocumentId() != null) {
            throw new DocumentReplacementConflictException();
        }
        return copy(
                fields, DocumentStatus.PUBLISHED, revision.next(),
                new ReplacementLink(oldDocumentId, null),
                audit.at(), audit.actor(), null, null, audit
        );
    }

    KnowledgeDocument archiveAsReplacedBy(Long newDocumentId, DocumentAudit audit) {
        Objects.requireNonNull(newDocumentId, "newDocumentId");
        Objects.requireNonNull(audit, "audit");
        if (status != DocumentStatus.PUBLISHED || replacement.replacedByDocumentId() != null) {
            throw new DocumentReplacementConflictException();
        }
        return copy(
                fields, DocumentStatus.ARCHIVED, revision.next(),
                new ReplacementLink(replacement.replacesDocumentId(), newDocumentId),
                publishedAt, publishedBy, audit.at(), audit.actor(), audit
        );
    }

    private KnowledgeDocument copy(
            KnowledgeDocumentFields nextFields,
            DocumentStatus nextStatus,
            DocumentRevision nextRevision,
            ReplacementLink nextReplacement,
            Instant nextPublishedAt,
            String nextPublishedBy,
            Instant nextArchivedAt,
            String nextArchivedBy,
            DocumentAudit audit
    ) {
        return new KnowledgeDocument(
                id, nextFields, nextStatus, nextRevision, nextReplacement,
                nextPublishedAt, nextPublishedBy, nextArchivedAt, nextArchivedBy,
                createdAt, audit.at(), createdBy, audit.actor()
        );
    }

    private void ensureNotArchived() {
        if (status == DocumentStatus.ARCHIVED) {
            throw new DocumentStateConflictException();
        }
    }

    private static void validateLifecycleAudit(
            DocumentStatus status,
            Instant publishedAt,
            String publishedBy,
            Instant archivedAt,
            String archivedBy
    ) {
        boolean publicationComplete = publishedAt != null && hasText(publishedBy);
        boolean publicationEmpty = publishedAt == null && !hasText(publishedBy);
        boolean archiveComplete = archivedAt != null && hasText(archivedBy);
        boolean archiveEmpty = archivedAt == null && !hasText(archivedBy);
        boolean valid = switch (status) {
            case DRAFT -> publicationEmpty && archiveEmpty;
            case PUBLISHED -> publicationComplete && archiveEmpty;
            case ARCHIVED -> archiveComplete && (publicationComplete || publicationEmpty);
        };
        if (!valid) {
            throw new IllegalArgumentException("document lifecycle audit does not match status");
        }
    }

    private static String requiredActor(String actor, String field) {
        if (!hasText(actor)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return actor.strip();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
