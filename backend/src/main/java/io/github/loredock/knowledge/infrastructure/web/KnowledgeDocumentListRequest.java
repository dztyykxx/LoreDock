package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;

/**
 * 普通知识列表查询参数；PROJECT 必须提交项目标识，分支为空时由项目能力解析为 main，GLOBAL 不得提交项目或分支。
 *
 * @param context 明确入口上下文
 * @param project 项目业务标识
 * @param branch 可选分支名
 * @param directory 可选逻辑目录，缺省表示全部文档，空字符串表示根目录
 * @param page 零基页码，默认 0
 * @param size 页容量，默认 20、最大 100
 */
public record KnowledgeDocumentListRequest(
        KnowledgeBrowseContextType context,
        String project,
        String branch,
        String directory,
        Integer page,
        Integer size
) {
}
