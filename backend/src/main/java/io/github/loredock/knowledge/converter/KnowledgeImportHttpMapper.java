package io.github.loredock.knowledge.converter;

import io.github.loredock.knowledge.model.response.KnowledgeImportBatchResponse;
import io.github.loredock.knowledge.model.response.KnowledgeImportItemResponse;
import io.github.loredock.knowledge.model.response.KnowledgeScopeResponse;
import io.github.loredock.knowledge.model.result.KnowledgeImportBatchView;
import io.github.loredock.knowledge.model.result.KnowledgeImportItemView;

/** 导入应用视图到安全 HTTP 响应的纯映射器。 */
public final class KnowledgeImportHttpMapper {

    private KnowledgeImportHttpMapper() {
    }

    /** @param batch 已完成批次视图；不含对象键和正文 */
    public static KnowledgeImportBatchResponse toResponse(KnowledgeImportBatchView batch) {
        return new KnowledgeImportBatchResponse(
                batch.id(), batch.originalFilename(),
                new KnowledgeScopeResponse(
                        batch.scope().type(), batch.scope().projectId(), batch.scope().branchId()),
                batch.directoryPrefix(), batch.status(), batch.succeededCount(), batch.failedCount(),
                batch.ignoredCount(), batch.items().stream().map(KnowledgeImportHttpMapper::toResponse).toList(),
                batch.createdAt(), batch.createdBy());
    }

    private static KnowledgeImportItemResponse toResponse(KnowledgeImportItemView item) {
        return new KnowledgeImportItemResponse(
                item.ordinal(), item.entryName(), item.status(), item.reason(), item.message(), item.documentId());
    }
}
