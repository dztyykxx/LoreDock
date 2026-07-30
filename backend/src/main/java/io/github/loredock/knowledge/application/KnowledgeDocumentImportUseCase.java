package io.github.loredock.knowledge.application;

import java.util.UUID;

/** 管理员同步导入与历史结果查询端口；入口层必须在读取上传正文前完成 ADMIN 授权。 */
public interface KnowledgeDocumentImportUseCase {

    /**
     * 导入单文件或 ZIP。外层大小、类型或批次结构失败时不持久化批次；通过批次检查后逐项独立提交。
     *
     * @param command 上传流与已解析默认值
     * @return 完整批次及三类结果
     */
    KnowledgeImportBatchView importDocuments(KnowledgeImportCommand command);

    /**
     * 查询历史结果；不存在时按导入批次不存在失败，不返回原始正文或对象键。
     *
     * @param batchId 批次 UUID
     * @return 已完成批次
     */
    KnowledgeImportBatchView getBatch(UUID batchId);
}
