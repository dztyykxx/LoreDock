package io.github.loredock.knowledge.service.search.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.knowledge.model.result.KnowledgeChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicKnowledgeChunkerTest {

    private final DeterministicKnowledgeChunker chunker = new DeterministicKnowledgeChunker();

    /**
     * 业务目的：Markdown 标题与段落边界应优先成为分块终点，避免在存在自然边界时截断一个完整业务段落。
     */
    @Test
    void markdownHeadingsAndParagraphsArePreferredBoundaries() {
        String first = "# 导出与恢复\n\n" + "甲".repeat(330);
        String second = "\n\n## 导入校验\n\n" + "乙".repeat(180);
        String body = first + second;

        List<KnowledgeChunk> chunks = chunker.chunk(body);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().content()).isEqualTo(first);
        assertThat(chunks.getFirst().endOffset()).isEqualTo(first.codePointCount(0, first.length()));
        assertThat(chunks.getLast().content()).contains("## 导入校验").endsWith("乙".repeat(180));
        System.out.printf("测试证据：场景=Markdown自然边界，分块数=%d，首块结束偏移=%d%n",
                chunks.size(), chunks.getFirst().endOffset());
    }

    /**
     * 业务目的：长正文必须严格限制为 400 code point 并保留 80 code point 重叠，
     * 防止 surrogate pair 被截断或数据库 offset 无法还原原文。
     */
    @Test
    void longUnicodeTextUsesSafeOffsetsMaximumAndOverlap() {
        String body = ("知识😀恢复流程。".repeat(120)) + "尾声";

        List<KnowledgeChunk> chunks = chunker.chunk(body);

        assertThat(chunks).hasSizeGreaterThan(2);
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = chunks.get(index);
            assertThat(chunk.chunkNo()).isEqualTo(index);
            assertThat(chunk.endOffset() - chunk.startOffset()).isLessThanOrEqualTo(400);
            assertThat(codePointSlice(body, chunk.startOffset(), chunk.endOffset())).isEqualTo(chunk.content());
            if (index > 0) {
                assertThat(chunks.get(index - 1).endOffset() - chunk.startOffset()).isEqualTo(80);
            }
        }
        assertThat(chunks.getLast().endOffset()).isEqualTo(body.codePointCount(0, body.length()));
        System.out.printf("测试证据：场景=Unicode长正文，codePoint数=%d，分块数=%d，最大块=%d，重叠=%d%n",
                body.codePointCount(0, body.length()), chunks.size(),
                chunks.stream().mapToInt(chunk -> chunk.endOffset() - chunk.startOffset()).max().orElseThrow(), 80);
    }

    /**
     * 业务目的：空正文不得生成占位分块，防止重建计数与实际可检索内容不一致。
     */
    @Test
    void blankBodyProducesNoChunksDeterministically() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk(" \n\t ")).isEmpty();
        assertThat(chunker.version()).isEqualTo("cjk-v1");
        System.out.println("测试证据：场景=空正文，分块数=0，策略版本=cjk-v1");
    }

    private String codePointSlice(String text, int start, int end) {
        int startIndex = text.offsetByCodePoints(0, start);
        int endIndex = text.offsetByCodePoints(0, end);
        return text.substring(startIndex, endIndex);
    }
}
