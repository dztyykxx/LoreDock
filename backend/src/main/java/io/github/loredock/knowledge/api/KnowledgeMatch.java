package io.github.loredock.knowledge.api;

import java.time.Instant;

/**
 * 供问答引用的有限知识结果。
 *
 * @param documentId 稳定文档标识
 * @param scope 当前知识范围
 * @param title 文档标题
 * @param snippet 有界知识片段
 * @param source 可公开来源字段
 * @param sourceUpdatedAt 来源更新时间
 * @param relevance 归一化相关性
 */
public record KnowledgeMatch(
        Long documentId,
        Scope scope,
        String title,
        String snippet,
        Source source,
        Instant sourceUpdatedAt,
        double relevance
) {

    /**
     * @param type 通用、项目或分支范围
     * @param projectIdentifier 项目或分支范围的项目标识
     * @param branch 分支范围的分支名
     */
    public record Scope(String type, String projectIdentifier, String branch) {
    }

    /**
     * @param type 人工、Wiki 或上传来源
     * @param wikiUrl 可公开 Wiki 地址
     * @param originalFilename 可公开原始文件名
     */
    public record Source(String type, String wikiUrl, String originalFilename) {
    }
}
