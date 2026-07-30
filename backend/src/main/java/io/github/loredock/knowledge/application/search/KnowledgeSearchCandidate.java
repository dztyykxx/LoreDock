package io.github.loredock.knowledge.application.search;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.KnowledgeScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 固定 generation 内的有界分块候选；融合后必须按文档折叠并实时复核资格。
 *
 * @param documentId 文档标识
 * @param chunkNo generation 内文档分块序号
 * @param startOffset 片段在投影正文中的 Unicode code point 起点
 * @param endOffset 片段在投影正文中的 Unicode code point 终点
 * @param content 有限分块正文
 * @param title 投影标题
 * @param tags 投影标签
 * @param source 投影来源
 * @param scope 投影范围
 * @param format 投影格式
 * @param sourceUpdatedAt 投影来源更新时间
 * @param rawScore 当前候选通道的原始分数，仅用于通道内排序
 */
public record KnowledgeSearchCandidate(
        UUID documentId,
        int chunkNo,
        int startOffset,
        int endOffset,
        String content,
        String title,
        List<DocumentTag> tags,
        DocumentSource source,
        KnowledgeScope scope,
        DocumentFormat format,
        Instant sourceUpdatedAt,
        double rawScore
) {
    public KnowledgeSearchCandidate {
        tags = List.copyOf(tags);
    }
}
