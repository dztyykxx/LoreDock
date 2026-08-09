package io.github.loredock.knowledge.api;

import java.time.Instant;
import java.util.List;

/**
 * 向 Agent 与 MCP 提供的受范围约束知识文档读取契约。
 *
 * <p>调用方只提交项目标识和业务查询参数；实现必须在查询阶段限制项目范围与文档状态，
 * 不接受服务器文件路径，也不把待处理草稿混入正式知识检索。</p>
 */
public interface KnowledgeDocumentAccessService {

    /**
     * 校验并读取同一项目内的待处理草稿。
     *
     * @param projectIdentifier 已启用项目标识
     * @param documentIds 待处理草稿标识
     * @return 按请求顺序返回的草稿正文
     * @throws IllegalArgumentException 选择为空、重复、跨项目、状态错误或文档不存在
     */
    List<DocumentContent> readDrafts(String projectIdentifier, List<Long> documentIds);

    /** @return 当前项目和通用范围内已发布文档的目录统计 */
    List<DirectoryEntry> listPublishedDirectories(String projectIdentifier, String prefix, int limit);

    /** @return 当前项目和通用范围内指定目录的已发布文档摘要 */
    List<DocumentSummary> listPublishedDocuments(String projectIdentifier, String directory, int limit);

    /**
     * 按 Unicode 码点游标分段读取当前项目和通用范围内的已发布文档。
     *
     * @param projectIdentifier 已启用项目标识
     * @param documentId 已发布文档标识
     * @param cursor 起始 Unicode 码点位置；为空时从 0 开始
     * @param maxCodePoints 本次最大返回码点数；为空时使用服务端默认值
     * @return 有明确边界和后续游标的 Markdown 分段
     */
    DocumentPage readPublishedPage(
            String projectIdentifier,
            Long documentId,
            Integer cursor,
            Integer maxCodePoints
    );

    /** @return 当前项目和通用范围内已发布 Markdown 的大小写不敏感关键词匹配 */
    List<KeywordMatch> grepPublished(
            String projectIdentifier,
            String keyword,
            String directory,
            List<Long> documentIds,
            int limit,
            int contextLines
    );

    /** 文档正文及稳定展示元数据。 */
    record DocumentContent(
            Long documentId,
            long revision,
            String title,
            String directory,
            String markdown,
            String originalFilename,
            Instant updatedAt
    ) { }

    /** 已发布文档的有界 Markdown 分段；nextCursor 为空表示已到文末。 */
    record DocumentPage(
            Long documentId,
            long revision,
            String title,
            String directory,
            String markdown,
            String originalFilename,
            Instant updatedAt,
            int cursor,
            Integer nextCursor,
            int totalCodePoints,
            boolean truncated
    ) { }

    /** 目录及其范围内的文档数量。 */
    record DirectoryEntry(String path, long documentCount) { }

    /** 已发布文档摘要。 */
    record DocumentSummary(Long documentId, String title, String directory, Instant updatedAt) { }

    /** 关键词命中行及有限上下文。 */
    record KeywordMatch(
            Long documentId,
            String title,
            int lineNumber,
            String context,
            boolean truncated
    ) { }
}
