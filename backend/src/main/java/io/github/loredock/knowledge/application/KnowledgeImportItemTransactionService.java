package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.ImportItemReason;
import io.github.loredock.knowledge.domain.ImportItemStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 导入条目的独立事务服务。成功项在同一新事务中创建文档、标签并写入批次关联，任一步失败整体回滚。
 */
@Service
public class KnowledgeImportItemTransactionService {

    private final KnowledgeDocumentCommandUseCase documentCommands;
    private final KnowledgeImportRepository imports;

    /**
     * @param documentCommands 复用正式文档创建规则的命令端口
     * @param imports 导入结果仓储
     */
    public KnowledgeImportItemTransactionService(
            KnowledgeDocumentCommandUseCase documentCommands,
            KnowledgeImportRepository imports
    ) {
        this.documentCommands = documentCommands;
        this.imports = imports;
    }

    /**
     * 创建一个草稿并原子写入成功关联。
     *
     * @throws KnowledgeScopeInvalidException 处理时范围已失效或分支不属于项目
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KnowledgeImportItemRecord createDraft(
            UUID batchId,
            KnowledgeImportCandidate candidate,
            KnowledgeImportOptions options
    ) {
        if (!imports.isScopeValid(options.scope())) {
            throw new KnowledgeScopeInvalidException();
        }
        KnowledgeDocumentView document = documentCommands.create(new CreateKnowledgeDocumentCommand(
                candidate.format(), candidate.title(), candidate.body(), candidate.directory(),
                options.tags(), candidate.source(), options.scope()));
        KnowledgeImportItemRecord item = new KnowledgeImportItemRecord(
                UUID.randomUUID(), batchId, candidate.ordinal(), candidate.entryName(),
                ImportItemStatus.SUCCEEDED, ImportItemReason.IMPORTED,
                "已导入为待审核草稿", document.id());
        imports.insertItem(item);
        return item;
    }

    /** 将失败或忽略证据放入独立事务，避免受相邻条目回滚影响。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KnowledgeImportItemRecord record(
            UUID batchId,
            int ordinal,
            String entryName,
            ImportItemStatus status,
            ImportItemReason reason,
            String message
    ) {
        KnowledgeImportItemRecord item = new KnowledgeImportItemRecord(
                UUID.randomUUID(), batchId, ordinal, entryName, status, reason, message, null);
        imports.insertItem(item);
        return item;
    }
}
