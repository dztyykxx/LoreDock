package io.github.loredock.knowledge.service;

import io.github.loredock.knowledge.converter.KnowledgeDocumentViewFactory;
import io.github.loredock.knowledge.config.KnowledgeIndexJobTypes;
import io.github.loredock.knowledge.exception.DocumentReplacementConflictException;
import io.github.loredock.knowledge.exception.DocumentStateConflictException;
import io.github.loredock.knowledge.exception.KnowledgeDocumentNotFoundException;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.ReplacementChain;
import io.github.loredock.knowledge.model.ReplacementPublicationPlan;
import io.github.loredock.knowledge.model.ReplacementPublicationPlanner;
import io.github.loredock.knowledge.model.command.ArchiveKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.command.BatchPublishKnowledgeDocumentsCommand;
import io.github.loredock.knowledge.model.command.PublishKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.result.BatchPublishKnowledgeDocumentsResult;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务化文档生命周期服务；替代发布按 Long 稳定锁行并在同一事务写入新旧聚合。
 */
@Service
public class KnowledgeDocumentLifecycleService {

    private static final String REPLACEMENT_UNIQUE_CONSTRAINT = "uq_knowledge_document_replaces_document";
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeDocumentLifecycleService.class);

    private final KnowledgeDocumentDataService documents;
    private final AuditMetadataFactory auditFactory;
    private final KnowledgeDocumentViewFactory views;
    private final KnowledgeIndexJobService indexJobs;
    private final ReplacementPublicationPlanner replacementPlanner = new ReplacementPublicationPlanner();

    /**
     * @param documents 支持条件更新和稳定锁行的文档仓储
     * @param auditFactory 可信生命周期审计工厂
     * @param views 应用视图工厂
     * @param indexJobs 发布或归档后的知识索引增量刷新任务
     */
    public KnowledgeDocumentLifecycleService(
            KnowledgeDocumentDataService documents,
            AuditMetadataFactory auditFactory,
            KnowledgeDocumentViewFactory views,
            KnowledgeIndexJobService indexJobs
    ) {
        this.documents = documents;
        this.auditFactory = auditFactory;
        this.views = views;
        this.indexJobs = indexJobs;
    }

    @Transactional
    public KnowledgeDocumentView publish(PublishKnowledgeDocumentCommand command) {
        KnowledgeDocumentView published = command.replacesDocumentId() == null
                ? publishSingle(command.documentId())
                : publishReplacement(command.documentId(), command.replacesDocumentId());
        refreshIndex();
        return published;
    }

    /**
     * 提交增量刷新任务，只重算本次发布或归档涉及的文档，避免管理员每次操作都全量重建索引。
     * 与草稿发布共用 single-flight 任务，重复提交会复用进行中任务。
     */
    private void refreshIndex() {
        indexJobs.submit(KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH);
    }

    /**
     * 原子发布一组人工勾选文档；不支持替代关系或自动索引。
     *
     * @param command 唯一文档标识集合
     * @return 新发布和幂等已发布数量
     */
    @Transactional
    public BatchPublishKnowledgeDocumentsResult publishBatch(BatchPublishKnowledgeDocumentsCommand command) {
        List<Long> requestedIds = command.documentIds();
        LOG.info("knowledge_document_batch_publish started requestedCount={}", requestedIds.size());
        List<KnowledgeDocument> locked = documents.findAllByIdsForUpdate(requestedIds);
        if (locked.size() != requestedIds.size()) {
            LOG.warn("knowledge_document_batch_publish rejected requestedCount={} lockedCount={} reason=not_found",
                    requestedIds.size(), locked.size());
            throw new KnowledgeDocumentNotFoundException();
        }
        if (locked.stream().anyMatch(document -> document.status() == DocumentStatus.ARCHIVED)) {
            LOG.warn("knowledge_document_batch_publish rejected requestedCount={} reason=archived_document",
                    requestedIds.size());
            throw new DocumentStateConflictException();
        }
        DocumentAudit audit = audit();
        int publishedCount = 0;
        int alreadyPublishedCount = 0;
        for (KnowledgeDocument current : locked) {
            if (current.status() == DocumentStatus.PUBLISHED) {
                alreadyPublishedCount++;
                continue;
            }
            KnowledgeDocument published = current.publish(audit);
            if (!documents.update(published, current.revision())) {
                throw new DocumentStateConflictException();
            }
            publishedCount++;
        }
        LOG.info("knowledge_document_batch_publish completed requestedCount={} publishedCount={} alreadyPublishedCount={}",
                requestedIds.size(), publishedCount, alreadyPublishedCount);
        // 全部已发布属于幂等重试，没有产生变更，不需要触发索引刷新。
        if (publishedCount > 0) {
            refreshIndex();
        }
        return new BatchPublishKnowledgeDocumentsResult(
                requestedIds.size(), publishedCount, alreadyPublishedCount);
    }

    @Transactional
    public KnowledgeDocumentView archive(ArchiveKnowledgeDocumentCommand command) {
        KnowledgeDocument current = locked(command.documentId());
        KnowledgeDocument archived = current.archive(audit());
        if (!documents.update(archived, current.revision())) {
            throw new DocumentStateConflictException();
        }
        // 普通浏览与正式索引读取均会再次查询实时 PUBLISHED 状态，因此提交后立即失去资格；
        // 同时提交增量刷新移除其在索引中的分块。
        refreshIndex();
        return views.create(archived);
    }

    private KnowledgeDocumentView publishSingle(Long documentId) {
        KnowledgeDocument current = locked(documentId);
        KnowledgeDocument published = current.publish(audit());
        if (!documents.update(published, current.revision())) {
            throw new DocumentStateConflictException();
        }
        return views.create(published);
    }

    private KnowledgeDocumentView publishReplacement(Long candidateId, Long oldDocumentId) {
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

    private KnowledgeDocument locked(Long documentId) {
        List<KnowledgeDocument> locked = documents.findAllByIdsForUpdate(List.of(documentId));
        return findLocked(locked, documentId);
    }

    private KnowledgeDocument findLocked(List<KnowledgeDocument> locked, Long documentId) {
        return locked.stream().filter(document -> document.id().equals(documentId)).findFirst()
                .orElseThrow(KnowledgeDocumentNotFoundException::new);
    }

    private ReplacementChain replacementChain(KnowledgeDocument oldDocument) {
        List<Long> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        KnowledgeDocument current = oldDocument;
        while (true) {
            if (!seen.add(current.id())) {
                throw new DocumentReplacementConflictException();
            }
            chain.add(current.id());
            Long previousId = current.replacement().replacesDocumentId();
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
