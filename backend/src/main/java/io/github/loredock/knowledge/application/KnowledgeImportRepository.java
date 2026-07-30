package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.ImportBatchStatus;
import io.github.loredock.knowledge.domain.KnowledgeScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 导入批次和条目结果仓储端口。 */
public interface KnowledgeImportRepository {

    /** 插入尚未处理条目的批次证据。 */
    void insertBatch(KnowledgeImportBatchRecord batch);

    /** 写入一个条目结果；成功关联由调用方文档事务保护。 */
    void insertItem(KnowledgeImportItemRecord item);

    /** 更新最终批次状态、计数和可信审计。 */
    void updateSummary(UUID batchId, ImportBatchStatus status, int succeeded, int failed, int ignored,
                       Instant updatedAt, String updatedBy);

    /** 按 ID 读取批次内部记录。 */
    Optional<KnowledgeImportBatchRecord> findBatch(UUID batchId);

    /** 按 ordinal 稳定读取批次条目。 */
    List<KnowledgeImportItemRecord> findItems(UUID batchId);

    /**
     * 在处理每个条目前实时复核范围主数据；分支必须仍然属于指定项目。
     */
    boolean isScopeValid(KnowledgeScope scope);
}
