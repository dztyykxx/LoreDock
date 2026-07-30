package io.github.loredock.knowledge.application.search.indexing;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 写入固定 generation 的单个可检索分块；词项保持字段边界，向量由仓储在写入前统一校验。
 *
 * @param generationId generation 标识
 * @param documentId 文档标识
 * @param chunkNo 文档内稳定序号
 * @param startOffset 投影正文 code point 起始偏移
 * @param endOffset 投影正文 code point 结束偏移（不含）
 * @param content 与偏移对应的原始分块正文
 * @param titleTerms 标题分析词项
 * @param tagTerms 标签分析词项
 * @param contentTerms 正文分析词项
 * @param embedding 512 维有限数值向量
 * @param scopeType 强范围类型
 * @param projectId 项目范围标识
 * @param branchId 分支范围标识
 * @param format 文档格式
 * @param sourceType 来源类型
 * @param normalizedTags 规范化标签
 * @param sourceUpdatedAt 投影来源更新时间
 */
public record KnowledgeSearchChunkWrite(
        UUID generationId,
        UUID documentId,
        int chunkNo,
        int startOffset,
        int endOffset,
        String content,
        List<String> titleTerms,
        List<String> tagTerms,
        List<String> contentTerms,
        float[] embedding,
        KnowledgeScopeType scopeType,
        UUID projectId,
        UUID branchId,
        DocumentFormat format,
        DocumentSourceType sourceType,
        List<String> normalizedTags,
        Instant sourceUpdatedAt
) {
    public KnowledgeSearchChunkWrite {
        titleTerms = List.copyOf(titleTerms);
        tagTerms = List.copyOf(tagTerms);
        contentTerms = List.copyOf(contentTerms);
        normalizedTags = List.copyOf(normalizedTags);
        embedding = Objects.requireNonNull(embedding, "embedding is required").clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
