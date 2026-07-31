package io.github.loredock.knowledge.service.search;

import io.github.loredock.knowledge.model.enums.KnowledgeSearchMatchedBy;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;
import io.github.loredock.knowledge.model.result.FusedKnowledgeSearchCandidate;
import io.github.loredock.knowledge.model.result.KnowledgeSearchCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定配置的 Reciprocal Rank Fusion，实现分块到文档折叠、稳定排序与有限 Unicode 片段。
 * 原始关键词分数和余弦分数只决定各自通道名次，不跨量纲直接相加。
 */
public final class ReciprocalRankFusion {

    /** 当前融合配置版本，与 generation 元数据共同固定查询语义。 */
    public static final String CONFIG_VERSION = "rrf-v1";
    /** 固定 RRF 常数，客户端不得覆盖。 */
    public static final int RRF_K = 60;
    /** 公开结果片段上限。 */
    public static final int MAX_SNIPPET_CODE_POINTS = 500;
    private static final int MIN_CANDIDATE_LIMIT = 50;
    /** 每路内部候选硬上限。 */
    public static final int MAX_CANDIDATE_LIMIT = 200;

    /**
     * 根据公开返回上限计算每路内部候选上限。
     *
     * @param resultLimit 已校验的 1～50 返回上限
     * @return 50～200 的服务端候选上限
     */
    public static int candidateLimit(int resultLimit) {
        if (resultLimit < 1 || resultLimit > 50) {
            throw new IllegalArgumentException("knowledge search result limit must be between 1 and 50");
        }
        return Math.min(Math.max(resultLimit * 5, MIN_CANDIDATE_LIMIT), MAX_CANDIDATE_LIMIT);
    }

    /**
     * 按固定模式融合已经稳定排序的分块候选。
     *
     * @param mode 搜索模式
     * @param keywordCandidates 关键词分块候选
     * @param semanticCandidates 语义分块候选
     * @param resultLimit 最大文档结果数
     * @return 已折叠、归一化并稳定排序的文档候选
     */
    public List<FusedKnowledgeSearchCandidate> fuse(
            KnowledgeSearchMode mode,
            List<KnowledgeSearchCandidate> keywordCandidates,
            List<KnowledgeSearchCandidate> semanticCandidates,
            int resultLimit
    ) {
        if (mode == null || keywordCandidates == null || semanticCandidates == null) {
            throw new IllegalArgumentException("knowledge fusion input is required");
        }
        candidateLimit(resultLimit);

        RankedChannel keyword = ranked(mode == KnowledgeSearchMode.SEMANTIC ? List.of() : keywordCandidates);
        RankedChannel semantic = ranked(mode == KnowledgeSearchMode.KEYWORD ? List.of() : semanticCandidates);
        int channelCount = mode == KnowledgeSearchMode.HYBRID ? 2 : 1;
        double theoreticalMaximum = channelCount * reciprocal(1);

        Map<Long, DocumentFusion> documents = new LinkedHashMap<>();
        keyword.documents().forEach((documentId, candidate) ->
                documents.computeIfAbsent(documentId, ignored -> new DocumentFusion()).keyword = candidate);
        semantic.documents().forEach((documentId, candidate) ->
                documents.computeIfAbsent(documentId, ignored -> new DocumentFusion()).semantic = candidate);

        List<FusedKnowledgeSearchCandidate> fused = new ArrayList<>(documents.size());
        documents.forEach((documentId, document) -> {
            double score = (document.keyword == null ? 0 : reciprocal(document.keyword.rank()))
                    + (document.semantic == null ? 0 : reciprocal(document.semantic.rank()));
            KnowledgeSearchCandidate best = bestCandidate(documentId, document, keyword, semantic);
            Snippet snippet = snippet(best.content());
            fused.add(new FusedKnowledgeSearchCandidate(
                    documentId, best, snippet.value(), snippet.truncated(),
                    Math.min(1.0, score / theoreticalMaximum), matchedBy(document)));
        });
        fused.sort(Comparator.comparingDouble(FusedKnowledgeSearchCandidate::relevance).reversed()
                .thenComparing(result -> result.bestCandidate().sourceUpdatedAt(), Comparator.reverseOrder())
                .thenComparing(FusedKnowledgeSearchCandidate::documentId));
        return List.copyOf(fused.subList(0, Math.min(resultLimit, fused.size())));
    }

    private RankedChannel ranked(List<KnowledgeSearchCandidate> candidates) {
        Map<Long, RankedCandidate> documents = new LinkedHashMap<>();
        Map<ChunkKey, RankedCandidate> chunks = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            KnowledgeSearchCandidate candidate = candidates.get(index);
            if (candidate == null || candidate.documentId() == null) {
                throw new IllegalArgumentException("knowledge search candidate is invalid");
            }
            RankedCandidate ranked = new RankedCandidate(candidate, index + 1);
            documents.putIfAbsent(candidate.documentId(), ranked);
            chunks.putIfAbsent(new ChunkKey(candidate.documentId(), candidate.chunkNo()), ranked);
        }
        return new RankedChannel(documents, chunks);
    }

    private KnowledgeSearchCandidate bestCandidate(
            Long documentId,
            DocumentFusion document,
            RankedChannel keyword,
            RankedChannel semantic
    ) {
        if (document.keyword == null) {
            return document.semantic.candidate();
        }
        if (document.semantic == null) {
            return document.keyword.candidate();
        }
        // 两路命中同一分块时优先使用共同证据，避免相关性来自一块而片段来自完全不同的一块。
        return keyword.chunks().entrySet().stream()
                .filter(entry -> entry.getKey().documentId().equals(documentId))
                .filter(entry -> semantic.chunks().containsKey(entry.getKey()))
                .min(Comparator.<Map.Entry<ChunkKey, RankedCandidate>>comparingInt(entry -> entry.getValue().rank()
                                + semantic.chunks().get(entry.getKey()).rank())
                        .thenComparingInt(entry -> entry.getKey().chunkNo()))
                .map(entry -> entry.getValue().candidate())
                .orElseGet(() -> {
                    if (document.keyword.rank() != document.semantic.rank()) {
                        return document.keyword.rank() < document.semantic.rank()
                                ? document.keyword.candidate() : document.semantic.candidate();
                    }
                    return document.keyword.candidate().chunkNo() <= document.semantic.candidate().chunkNo()
                            ? document.keyword.candidate() : document.semantic.candidate();
                });
    }

    private KnowledgeSearchMatchedBy matchedBy(DocumentFusion document) {
        if (document.keyword != null && document.semantic != null) {
            return KnowledgeSearchMatchedBy.BOTH;
        }
        return document.keyword != null ? KnowledgeSearchMatchedBy.KEYWORD : KnowledgeSearchMatchedBy.SEMANTIC;
    }

    private double reciprocal(int rank) {
        return 1.0 / (RRF_K + rank);
    }

    private Snippet snippet(String content) {
        if (content == null) {
            throw new IllegalArgumentException("knowledge candidate content is required");
        }
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints <= MAX_SNIPPET_CODE_POINTS) {
            return new Snippet(content, false);
        }
        int end = content.offsetByCodePoints(0, MAX_SNIPPET_CODE_POINTS);
        return new Snippet(content.substring(0, end), true);
    }

    private record RankedCandidate(KnowledgeSearchCandidate candidate, int rank) {
    }

    private record RankedChannel(
            Map<Long, RankedCandidate> documents,
            Map<ChunkKey, RankedCandidate> chunks
    ) {
    }

    private record ChunkKey(Long documentId, int chunkNo) {
    }

    private record Snippet(String value, boolean truncated) {
    }

    private static final class DocumentFusion {
        private RankedCandidate keyword;
        private RankedCandidate semantic;
    }
}
