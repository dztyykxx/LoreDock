package io.github.loredock.knowledge.service;

import io.github.loredock.knowledge.converter.KnowledgeDocumentViewFactory;
import io.github.loredock.knowledge.exception.DocumentStateConflictException;
import io.github.loredock.knowledge.exception.KnowledgeDocumentNotFoundException;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.KnowledgeDocumentFields;
import io.github.loredock.knowledge.model.command.CreateKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.command.EditKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务化知识文档创建与编辑服务；范围必须由入口先解析，主体与标签通过同一仓储事务保存。
 */
@Service
public class KnowledgeDocumentCommandService {

    private final KnowledgeDocumentDataService documents;
    private final AuditMetadataFactory auditFactory;
    private final KnowledgeDocumentViewFactory views;

    /**
     * @param documents 文档仓储端口
     * @param auditFactory 可信时间与操作者工厂
     * @param views 应用视图工厂
     */
    public KnowledgeDocumentCommandService(
            KnowledgeDocumentDataService documents,
            AuditMetadataFactory auditFactory,
            KnowledgeDocumentViewFactory views
    ) {
        this.documents = documents;
        this.auditFactory = auditFactory;
        this.views = views;
    }

    @Transactional
    public KnowledgeDocumentView create(CreateKnowledgeDocumentCommand command) {
        KnowledgeDocumentFields fields = fields(command);
        AuditMetadata audit = auditFactory.created();
        KnowledgeDocument document = documents.insertDraft(
                fields, new DocumentAudit(audit.createdAt(), audit.createdBy()));
        return views.create(document);
    }

    @Transactional
    public KnowledgeDocumentView edit(EditKnowledgeDocumentCommand command) {
        KnowledgeDocument current = documents.findById(command.documentId())
                .orElseThrow(KnowledgeDocumentNotFoundException::new);
        AuditMetadata updateAudit = auditFactory.updated(new AuditMetadata(
                current.createdAt(), current.updatedAt(), current.createdBy(), current.updatedBy()));
        KnowledgeDocument edited = current.edit(fields(command),
                new DocumentAudit(updateAudit.updatedAt(), updateAudit.updatedBy()));
        if (!documents.update(edited, current.revision())) {
            // revision 条件失败说明读取后已有并发变更，不能用过期全量 PUT 覆盖新状态。
            throw new DocumentStateConflictException();
        }
        // 已发布文档编辑仍保持发布状态；修订变化由后续索引同步端口派生为 STALE。
        return views.create(edited);
    }

    private KnowledgeDocumentFields fields(CreateKnowledgeDocumentCommand command) {
        return new KnowledgeDocumentFields(
                command.format(), command.title(), command.body(), command.directory(),
                command.tags(), command.source(), command.scope());
    }

    private KnowledgeDocumentFields fields(EditKnowledgeDocumentCommand command) {
        return new KnowledgeDocumentFields(
                command.format(), command.title(), command.body(), command.directory(),
                command.tags(), command.source(), command.scope());
    }
}
