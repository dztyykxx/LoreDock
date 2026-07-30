package io.github.loredock.knowledge.application.search.indexing;

/**
 * 可追溯到投影正文 code point 范围的确定性分块。
 *
 * @param chunkNo 文档内从零开始的稳定序号
 * @param startOffset 正文 code point 起始偏移
 * @param endOffset 正文 code point 结束偏移（不含）
 * @param content 与偏移严格对应的原始正文片段
 */
public record KnowledgeChunk(int chunkNo, int startOffset, int endOffset, String content) {
}
