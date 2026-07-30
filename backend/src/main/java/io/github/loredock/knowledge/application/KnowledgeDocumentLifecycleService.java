package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentAudit;
import io.github.loredock.knowledge.domain.DocumentReplacementConflictException;
import io.github.loredock.knowledge.domain.DocumentStateConflictException;
import io.github.loredock.knowledge.domain.KnowledgeDocument;
import io.github.loredock.knowledge.domain.ReplacementChain;
import io.github.loredock.knowledge.domain.ReplacementPublicationPlan;
import io.github.loredock.knowledge.domain.ReplacementPublicationPlanner;
import io.github.loredock.platform.audit.AuditMetadata;
import io.github.loredock.platform.audit.AuditMetadataFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 事务化文档生命周期服务；替代发布按 UUID 稳定锁行并在同一事务写入新旧聚合。
 */
@Service
public class KnowledgeDocumentLifecycleService implements KnowledgeDocumentLifecycleUseCase {

    private static final String REPLACEMENT_UNIQUE_CONSTRAINT = "uq_knowledge_document_replaces_document";

    private final KnowledgeDocumentRepository documents;
    private final AuditMetadataFactory auditFactory;
    private final KnowledgeDocumentViewFactory views;
    private final ReplacementPublicationPlanner replacementPlanner = new ReplacementPublicationPlanner();

    /**
     * @param documents 支持条件更新和稳定锁行的文档仓储
     * @param auditFactory 可信生命周期审计工厂
     * @param views 应用视图工厂
     */
    public KnowledgeDocumentLifecycleService(
            KnowledgeDocumentRepository documents,
            AuditMetadataFactory auditFactory,
            KnowledgeDocumentViewFactory views
    ) {
        this.documents = documents;
        this.auditFactory = auditFactory;
        this.views = views;
    }

    @Override
    @Transactional
    public KnowledgeDocumentView publish(PublishKnowledgeDocumentCommand command) {
        if (command.replacesDocumentId() == null) {
            return publishSingle(command.documentId());
        }
        return publishReplacement(command.documentId(), command.replacesDocumentId());
    }

    @Override
    @Transactional
    public KnowledgeDocumentView archive(ArchiveKnowledgeDocumentCommand command) {
        KnowledgeDocument current = locked(command.documentId());
        KnowledgeDocument archived = current.archive(audit());
        if (!documents.update(archived, current.revision())) {
            throw new DocumentStateConflictException();
        }
        // 普通浏览与正式索引读取均会再次查询实时 PUBLISHED 状态，因此提交后立即失去资格。
        return views.create(archived);
    }

    private KnowledgeDocumentView publishSingle(UUID documentId) {
        KnowledgeDocument current = locked(documentId);
        KnowledgeDocument published = current.publish(audit());
        if (!documents.update(published, current.revision())) {
            throw new DocumentStateConflictException();
        }
        return views.create(published);
    }

    private KnowledgeDocumentView publishReplacement(UUID candidateId, UUID oldDocumentId) {
        List<KnowledgeDocument> locked = documents.findAllByIdsForUpdate(List.of(candidateId, oldDocumentId));
        KnowledgeDocument candidate = findLocked(locked, candidateId);
        KnowledgeDocument oldDocument = findLocked(locked, oldDocumentId);
        ReplacementPublicationPlan plan = replacementPlanner.plan(
                candidate, oldDocument, replacementChain(oldDocument), audit());
        try {
            if (!documents.update(plan.publishedDocument(), candidate.revision())) {
                throw new DocumentReplacementConflictException();
            }
            if (!documents.update(plan.archivedDocument(), oldDocument.revision())) {
                throw new DocumentReplacementConflictException();
            }
        } catch (DataIntegrityViolationException failure) {
            // 只把“一个旧文档最多一个当前替代者”的命名约束映射为业务冲突，其他数据库失败保留原语义。
            if (causedByNamedConstraint(failure, REPLACEMENT_UNIQUE_CONSTRAINT)) {
                throw new DocumentReplacementConflictException();
            }
            throw failure;
        }
        return views.create(plan.publishedDocument());
    }

    private KnowledgeDocument locked(UUID documentId) {
        List<KnowledgeDocument> locked = documents.findAllByIdsForUpdate(List.of(documentId));
        return findLocked(locked, documentId);
    }

    private KnowledgeDocument findLocked(List<KnowledgeDocument> locked, UUID documentId) {
        return locked.stream().filter(document -> document.id().equals(documentId)).findFirst()
                .orElseThrow(KnowledgeDocumentNotFoundException::new);
    }

    private ReplacementChain replacementChain(KnowledgeDocument oldDocument) {
        List<UUID> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        KnowledgeDocument current = oldDocument;
        while (true) {
            if (!seen.add(current.id())) {
                throw new DocumentReplacementConflictException();
            }
            chain.add(current.id());
            UUID previousId = current.replacement().replacesDocumentId();
            if (previousId == null) {
                return new ReplacementChain(chain);
            }
            current = documents.findById(previousId)
                    .orElseThrow(DocumentReplacementConflictException::new);
        }
    }

    private DocumentAudit audit() {
        AuditMetadata audit = auditFactory.created();
        return new DocumentAudit(audit.createdAt(), audit.createdBy());
    }

    private boolean causedByNamedConstraint(Throwable failure, String constraintName) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
