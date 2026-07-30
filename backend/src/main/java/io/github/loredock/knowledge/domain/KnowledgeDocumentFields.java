package io.github.loredock.knowledge.domain;

/**
 * 可编辑文档字段的不可变集合，供创建、编辑和导入复用同一领域边界。
 *
 * @param format 正文格式
 * @param title 标题
 * @param body 正文
 * @param directory 逻辑目录
 * @param tags 标签集合
 * @param source 来源
 * @param scope 已解析范围
 */
public record KnowledgeDocumentFields(
        DocumentFormat format,
        DocumentTitle title,
        DocumentBody body,
        DocumentDirectory directory,
        DocumentTags tags,
        DocumentSource source,
        KnowledgeScope scope
) {
    public KnowledgeDocumentFields {
        java.util.Objects.requireNonNull(format, "format");
        java.util.Objects.requireNonNull(title, "title");
        java.util.Objects.requireNonNull(body, "body");
        java.util.Objects.requireNonNull(directory, "directory");
        java.util.Objects.requireNonNull(tags, "tags");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(scope, "scope");
    }
}
