package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.DocumentFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 创建和全量编辑共用的 HTTP 请求；所有字段作为一个事务整体校验和保存，失败不得部分更新。
 *
 * @param format MARKDOWN 或 PLAIN_TEXT
 * @param title 标题，最多 200 个 Unicode 码点
 * @param body 非空纯文本正文，最多 2 Mi 个 Unicode 码点
 * @param directory 逻辑目录，最多 1000 个 Unicode 码点
 * @param tags 最多 20 个标签，每项最多 100 个 Unicode 码点且大小写无关唯一
 * @param source 来源信息
 * @param scope 三级范围输入
 */
public record KnowledgeDocumentWriteRequest(
        @NotNull DocumentFormat format,
        @NotBlank String title,
        @NotBlank String body,
        String directory,
        @NotNull List<String> tags,
        @Valid @NotNull DocumentSourceRequest source,
        @Valid @NotNull KnowledgeScopeRequest scope
) {
}
