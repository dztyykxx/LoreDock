package io.github.loredock.knowledge.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTag;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMatchedBy;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;
import io.github.loredock.knowledge.model.result.FusedKnowledgeSearchCandidate;
import io.github.loredock.knowledge.model.result.KnowledgeSearchCandidate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    /**
     * 业务目的：固定 k=60 的 RRF 必须保留只被一路召回的文档，并给予双路共同命中文档额外优势。
     */
    @Test
    void hybridFusionKeepsSingleChannelCandidatesAndRewardsBothChannels() {
        Long keywordOnly = id(1);
        Long both = id(2);
        Long semanticOnly = id(3);
        List<KnowledgeSearchCandidate> keyword = List.of(
                candidate(keywordOnly, 0, "关键词片段", 9999, 0),
                candidate(both, 0, "共同关键词片段", 0.01, 0));
        List<KnowledgeSearchCandidate> semantic = List.of(
                candidate(both, 1, "共同语义片段", -200, 0),
                candidate(semanticOnly, 0, "语义片段", 1, 0));

        List<FusedKnowledgeSearchCandidate> results = fusion.fuse(
                KnowledgeSearchMode.HYBRID, keyword, semantic, 10);

        assertThat(results).extracting(FusedKnowledgeSearchCandidate::documentId)
                .containsExactly(both, keywordOnly, semanticOnly);
        assertThat(results).extracting(FusedKnowledgeSearchCandidate::matchedBy)
                .containsExactly(KnowledgeSearchMatchedBy.BOTH,
                        KnowledgeSearchMatchedBy.KEYWORD, KnowledgeSearchMatchedBy.SEMANTIC);
        assertThat(results.getFirst().relevance()).isCloseTo(
                (1.0 / 62 + 1.0 / 61) / (2.0 / 61), within(0.0000001));
        System.out.printf("测试证据：场景=混合RRF双路加成，k=%d，排序=%s，单路候选保留=2%n",
                ReciprocalRankFusion.RRF_K,
                results.stream().map(FusedKnowledgeSearchCandidate::documentId).toList());
    }

    /**
     * 业务目的：融合只能使用通道内名次，不能直接相加关键词分数与余弦分数这两种不同量纲。
     */
    @Test
    void fusionUsesRanksInsteadOfIncomparableRawScores() {
        Long first = id(10);
        Long second = id(11);
        List<FusedKnowledgeSearchCandidate> results = fusion.fuse(
                KnowledgeSearchMode.KEYWORD,
                List.of(candidate(first, 0, "第一", -1_000_000, 0),
                        candidate(second, 0, "第二", 1_000_000, 0)),
                List.of(), 10);

        assertThat(results).extracting(FusedKnowledgeSearchCandidate::documentId)
                .containsExactly(first, second);
        assertThat(results.getFirst().relevance()).isCloseTo(1.0, within(0.0000001));
        assertThat(results.get(1).relevance()).isBetween(0.0, 1.0);
        System.out.printf("测试证据：场景=跨量纲RRF，原始分数方向相反，最终名次=%s，最高归一化=%.1f%n",
                results.stream().map(FusedKnowledgeSearchCandidate::documentId).toList(),
                results.getFirst().relevance());
    }

    /**
     * 业务目的：同文档多个分块必须折叠为一条，并保留每路最小名次及贡献更高的最佳片段来源。
     */
    @Test
    void duplicateChunksCollapseToDocumentAndChooseHighestContributionSnippet() {
        Long document = id(20);
        Long filler = id(21);
        List<FusedKnowledgeSearchCandidate> results = fusion.fuse(
                KnowledgeSearchMode.HYBRID,
                List.of(candidate(filler, 0, "填充", 5, 0),
                        candidate(document, 2, "关键词最佳片段", 4, 0),
                        candidate(document, 3, "关键词次要片段", 3, 0)),
                List.of(candidate(document, 7, "语义最佳片段", 0.99, 0)), 10);

        assertThat(results).filteredOn(result -> result.documentId().equals(document)).singleElement()
                .satisfies(result -> {
                    assertThat(result.matchedBy()).isEqualTo(KnowledgeSearchMatchedBy.BOTH);
                    assertThat(result.bestCandidate().chunkNo()).isEqualTo(7);
                    assertThat(result.snippet()).isEqualTo("语义最佳片段");
                });
        System.out.printf("测试证据：场景=文档分块折叠，输入分块数=4，输出文档数=%d，最佳分块=%d%n",
                results.size(), results.stream().filter(result -> result.documentId().equals(document))
                        .findFirst().orElseThrow().bestCandidate().chunkNo());
    }

    /**
     * 业务目的：混合检索两路命中同一分块时必须优先使用共同证据，避免返回与双路相关性来源不一致的片段。
     */
    @Test
    void hybridFusionPrefersAChunkHitByBothChannels() {
        Long document = id(25);
        FusedKnowledgeSearchCandidate result = fusion.fuse(
                KnowledgeSearchMode.HYBRID,
                List.of(candidate(document, 1, "关键词第一片段", 10, 0),
                        candidate(document, 5, "两路共同片段", 9, 0)),
                List.of(candidate(document, 2, "语义第一片段", 1, 0),
                        candidate(document, 5, "两路共同片段", 0.9, 0)), 1).getFirst();

        assertThat(result.bestCandidate().chunkNo()).isEqualTo(5);
        assertThat(result.snippet()).isEqualTo("两路共同片段");
        System.out.printf("测试证据：场景=两路共同分块优先，文档=%s，最佳分块=%d，matchedBy=%s%n",
                document, result.bestCandidate().chunkNo(), result.matchedBy());
    }

    /**
     * 业务目的：片段必须在 500 个 Unicode code point 边界安全截断，不能切断代理对或返回完整长正文。
     */
    @Test
    void snippetTruncatesAtFiveHundredUnicodeCodePoints() {
        String content = "知".repeat(499) + "😀" + "尾部不得返回";

        FusedKnowledgeSearchCandidate result = fusion.fuse(
                KnowledgeSearchMode.SEMANTIC, List.of(),
                List.of(candidate(id(30), 0, content, 1, 0)), 1).getFirst();

        assertThat(result.snippet().codePointCount(0, result.snippet().length())).isEqualTo(500);
        assertThat(result.snippet()).endsWith("😀").doesNotContain("尾部");
        assertThat(result.truncated()).isTrue();
        System.out.printf("测试证据：场景=Unicode片段上限，原始码点=%d，返回码点=%d，truncated=%s%n",
                content.codePointCount(0, content.length()),
                result.snippet().codePointCount(0, result.snippet().length()), result.truncated());
    }

    /**
     * 业务目的：融合分数相同时必须依次按来源更新时间降序、Long 升序，保证同 generation 重复查询顺序一致。
     */
    @Test
    void equalScoresUseUpdatedTimeThenUuidForStableOrdering() {
        Long old = id(40);
        Long newerHighUuid = id(42);
        Long newerLowUuid = id(41);
        Long newest = id(43);
        Instant recent = Instant.parse("2026-07-30T01:00:00Z");
        List<FusedKnowledgeSearchCandidate> results = fusion.fuse(
                KnowledgeSearchMode.HYBRID,
                List.of(candidate(old, 0, "旧", 1, 0),
                        candidate(newerHighUuid, 0, "新高", 1, 1),
                        candidate(newerLowUuid, 0, "新低", 1, 1),
                        candidate(newest, 0, "最新", 1, 1)),
                List.of(candidate(newest, 0, "最新", 1, 1),
                        candidate(newerLowUuid, 0, "新低", 1, 1),
                        candidate(newerHighUuid, 0, "新高", 1, 1),
                        candidate(old, 0, "旧", 1, 0)), 10);

        assertThat(results).extracting(FusedKnowledgeSearchCandidate::documentId)
                .containsExactly(newest, old, newerLowUuid, newerHighUuid);
        assertThat(results.getFirst().bestCandidate().sourceUpdatedAt()).isEqualTo(recent);
        System.out.printf("测试证据：场景=融合稳定并列排序，相同分数文档对=2，顺序=%s%n",
                results.stream().map(FusedKnowledgeSearchCandidate::documentId).toList());
    }

    /**
     * 业务目的：候选上限必须由统一版本化配置计算，返回 limit 无论大小都不能产生低于 50 或高于 200 的候选集。
     */
    @Test
    void candidateLimitIsCentralizedAndBoundedByFusionConfiguration() {
        assertThat(ReciprocalRankFusion.candidateLimit(1)).isEqualTo(50);
        assertThat(ReciprocalRankFusion.candidateLimit(20)).isEqualTo(100);
        assertThat(ReciprocalRankFusion.candidateLimit(50)).isEqualTo(200);
        assertThat(ReciprocalRankFusion.CONFIG_VERSION).isEqualTo("rrf-v1");
        System.out.printf("测试证据：场景=候选上限配置，limit=1/20/50，候选数=50/100/200，版本=%s%n",
                ReciprocalRankFusion.CONFIG_VERSION);
    }

    private KnowledgeSearchCandidate candidate(
            Long documentId,
            int chunkNo,
            String content,
            double rawScore,
            int updatedHour
    ) {
        return new KnowledgeSearchCandidate(
                documentId, chunkNo, 0, content.codePointCount(0, content.length()), content,
                "标题", List.of(DocumentTag.of("测试")),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, null),
                KnowledgeScope.global(), DocumentFormat.MARKDOWN,
                Instant.parse("2026-07-30T0" + updatedHour + ":00:00Z"), rawScore);
    }

    private Long id(int suffix) {
        return (long) suffix;
    }
}
