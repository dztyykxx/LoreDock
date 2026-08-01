package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;

/**
 * 管理员知识工作区浏览输入；范围由项目主数据解析，目录固定使用自身及后代子树语义。
 *
 * @param context 当前通用或项目上下文
 * @param directory 可选目录；空值表示全部文档
 * @param page 零基页码
 * @param size 页容量
 */
public record AdminBrowseKnowledgeDocumentsQuery(
        KnowledgeBrowseContext context,
        DocumentDirectory directory,
        int page,
        int size
) {
}
