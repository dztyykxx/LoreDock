package io.github.loredock.knowledge.application;

/** 管理员创建与编辑知识文档的应用端口；入口层必须先完成 ADMIN 授权和范围解析。 */
public interface KnowledgeDocumentCommandUseCase {

    /**
     * 创建新的 DRAFT；该操作不幂等，失败时不得留下部分标签、来源或审计记录。
     *
     * @param command 完整创建输入
     * @return 创建后的管理详情
     */
    KnowledgeDocumentView create(CreateKnowledgeDocumentCommand command);

    /**
     * 全量编辑未归档文档；同值请求幂等，归档文档以状态冲突失败。
     *
     * @param command 完整编辑输入
     * @return 当前管理详情
     */
    KnowledgeDocumentView edit(EditKnowledgeDocumentCommand command);
}
