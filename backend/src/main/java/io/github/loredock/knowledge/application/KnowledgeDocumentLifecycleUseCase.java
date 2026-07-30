package io.github.loredock.knowledge.application;

/** 管理员显式控制发布、替代发布和归档的事务化应用端口。 */
public interface KnowledgeDocumentLifecycleUseCase {

    /**
     * 发布文档；重复发布同一当前结果幂等，替代冲突或归档终态以业务冲突失败。
     *
     * @param command 发布及可选替代输入
     * @return 提交后的真实管理详情
     */
    KnowledgeDocumentView publish(PublishKnowledgeDocumentCommand command);

    /**
     * 归档草稿或已发布文档；重复归档幂等，提交后立即失去普通浏览和正式索引资格。
     *
     * @param command 归档输入
     * @return 提交后的真实管理详情
     */
    KnowledgeDocumentView archive(ArchiveKnowledgeDocumentCommand command);
}
