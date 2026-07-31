package io.github.loredock.knowledge.service.search.indexing;

import io.github.loredock.knowledge.model.result.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * `cjk-v1` 确定性分块器。
 *
 * <p>偏移量统一使用 Unicode code point，避免把 emoji 等代理对从中间截断。每块最多
 * 400 code point，相邻块保留 80 code point 重叠；仅在块的后半段查找 Markdown
 * 段落边界，从而兼顾语义完整性和稳定的块大小。</p>
 */
public final class DeterministicKnowledgeChunker {

    private static final int MAX_CHUNK_CODE_POINTS = 400;
    private static final int OVERLAP_CODE_POINTS = 80;
    private static final int NATURAL_BOUNDARY_SEARCH_START = 320;

    public List<KnowledgeChunk> chunk(String body) {
        Objects.requireNonNull(body, "body must not be null");
        if (body.isBlank()) {
            return List.of();
        }

        int totalCodePoints = body.codePointCount(0, body.length());
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int startOffset = 0;
        int chunkNo = 0;
        while (startOffset < totalCodePoints) {
            int hardEndOffset = Math.min(startOffset + MAX_CHUNK_CODE_POINTS, totalCodePoints);
            int endOffset = hardEndOffset;
            if (hardEndOffset < totalCodePoints) {
                endOffset = preferredBoundary(body, startOffset, hardEndOffset);
            }

            chunks.add(new KnowledgeChunk(
                    chunkNo++,
                    startOffset,
                    endOffset,
                    codePointSlice(body, startOffset, endOffset)
            ));
            if (endOffset == totalCodePoints) {
                break;
            }
            startOffset = endOffset - OVERLAP_CODE_POINTS;
        }
        return List.copyOf(chunks);
    }

    public String version() {
        return "cjk-v1";
    }

    private int preferredBoundary(String body, int startOffset, int hardEndOffset) {
        int searchStartOffset = Math.min(startOffset + NATURAL_BOUNDARY_SEARCH_START, hardEndOffset);
        int searchStartIndex = body.offsetByCodePoints(0, searchStartOffset);
        int hardEndIndex = body.offsetByCodePoints(0, hardEndOffset);
        String searchWindow = body.substring(searchStartIndex, hardEndIndex);

        // 新标题开始比普通段落边界更稳定，避免把标题挂在上一块末尾而正文落到下一块。
        int boundaryIndex = searchWindow.lastIndexOf("\n\n#");
        if (boundaryIndex < 0) {
            boundaryIndex = searchWindow.lastIndexOf("\n\n");
        }
        if (boundaryIndex < 0) {
            return hardEndOffset;
        }
        return searchStartOffset + searchWindow.codePointCount(0, boundaryIndex);
    }

    private String codePointSlice(String body, int startOffset, int endOffset) {
        int startIndex = body.offsetByCodePoints(0, startOffset);
        int endIndex = body.offsetByCodePoints(0, endOffset);
        return body.substring(startIndex, endIndex);
    }
}
