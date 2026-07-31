package io.github.loredock.knowledge.service.search;

import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.service.ActiveCodeSnapshotQueryService;
import io.github.loredock.knowledge.exception.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.exception.KnowledgeIndexUnavailableException;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchWarning;
import io.github.loredock.knowledge.model.request.KnowledgeSearchCandidateRequest;
import io.github.loredock.knowledge.model.request.KnowledgeSearchFilters;
import io.github.loredock.knowledge.model.request.KnowledgeSearchQuery;
import io.github.loredock.knowledge.model.response.KnowledgeSearchResponse;
import io.github.loredock.knowledge.model.result.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.model.result.FusedKnowledgeSearchCandidate;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingVector;
import io.github.loredock.knowledge.model.result.KnowledgeSearchCandidate;
import io.github.loredock.knowledge.model.result.KnowledgeSearchContext;
import io.github.loredock.knowledge.model.result.KnowledgeSearchResolvedScope;
import io.github.loredock.knowledge.model.result.KnowledgeSearchResult;
import io.github.loredock.knowledge.model.result.KnowledgeSearchResultScope;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import io.github.loredock.knowledge.service.KnowledgeCandidateDataService;
import io.github.loredock.knowledge.service.KnowledgeSearchEligibilityService;
import io.github.loredock.knowledge.service.KnowledgeSearchIndexDataService;
import io.github.loredock.knowledge.service.project.ProjectKnowledgeScopeResolver;
import io.github.loredock.platform.web.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 知识搜索应用服务：先解析强范围并固定活动 generation，再按模式读取有界候选、RRF 折叠，
 * 最后通过事实表批量复核实时发布与范围资格。任何路径都不扩大范围或读取完整当前正文。
 */
@Service
public class KnowledgeSearchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeSearchService.class);
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_QUERY_CODE_POINTS = 500;
    private static final int MAX_TAGS = 10;

    private final ProjectKnowledgeScopeResolver scopes;
    private final KnowledgeSearchIndexDataService generations;
    private final KnowledgeCandidateDataService keywords;
    private final KnowledgeCandidateDataService semantics;
    private final KnowledgeEmbeddingService embedding;
    private final KnowledgeSearchEligibilityService eligibility;
    private final ActiveCodeSnapshotQueryService codeSnapshots;
    private final ReciprocalRankFusion fusion;

    /**
     * @param scopes 项目与分支主数据范围解析器
     * @param generations 完整活动检索 generation 读取端口
     * @param keywords 关键词候选端口
     * @param semantics 精确语义候选端口
     * @param embedding 离线查询 Embedding 端口
     * @param eligibility 当前事实表资格复核端口
     * @param codeSnapshots 项目分支活动代码快照状态端口
     * @param fusion 固定版本 RRF 融合器
     */
    public KnowledgeSearchService(
            ProjectKnowledgeScopeResolver scopes,
            KnowledgeSearchIndexDataService generations,
            KnowledgeCandidateDataService keywords,
            KnowledgeCandidateDataService semantics,
            KnowledgeEmbeddingService embedding,
            KnowledgeSearchEligibilityService eligibility,
            ActiveCodeSnapshotQueryService codeSnapshots,
            ReciprocalRankFusion fusion
    ) {
        this.scopes = scopes;
        this.generations = generations;
        this.keywords = keywords;
        this.semantics = semantics;
        this.embedding = embedding;
        this.eligibility = eligibility;
        this.codeSnapshots = codeSnapshots;
        this.fusion = fusion;
    }

    public KnowledgeSearchResponse search(KnowledgeSearchQuery query) {
        PreparedQuery prepared = prepare(query);
        long started = System.nanoTime();
        String traceId = traceId();
        LOGGER.info("knowledge_search started traceId={} operation=knowledge_search context={} mode={} "
                        + "queryLength={} queryHash={} tagCount={} format={} sourceType={} limit={}",
                traceId, prepared.contextType(), prepared.mode(), codePoints(prepared.query()),
                queryHash(prepared.query()), prepared.filters().tags().size(), prepared.filters().format(),
                prepared.filters().sourceType(), prepared.limit());
        try {
            return execute(prepared, traceId, started);
        } catch (ApplicationException exception) {
            LOGGER.warn("knowledge_search failed traceId={} operation=knowledge_search context={} mode={} "
                            + "errorCode={} elapsedMs={}",
                    traceId, prepared.contextType(), prepared.mode(), exception.errorCode().name(), elapsed(started));
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("knowledge_search failed traceId={} operation=knowledge_search context={} mode={} "
                            + "errorCode=KNOWLEDGE_INDEX_UNAVAILABLE elapsedMs={}",
                    traceId, prepared.contextType(), prepared.mode(), elapsed(started));
            throw new KnowledgeIndexUnavailableException(exception);
        }
    }

    private KnowledgeSearchResponse execute(PreparedQuery query, String traceId, long started) {
        KnowledgeBrowseContext browse = scopes.resolveBrowse(
                query.contextType(), query.projectIdentifier(), query.requestedBranch());
        KnowledgeSearchResolvedScope scope = resolvedScope(query, browse);
        LOGGER.info("knowledge_search_scope resolved traceId={} operation=knowledge_search context={} "
                        + "projectId={} branchId={} branch={}",
                traceId, scope.contextType(), scope.projectId(), scope.branchId(), scope.branch());

        ActiveKnowledgeSearchGeneration generation = activeGeneration();
        if (!ReciprocalRankFusion.CONFIG_VERSION.equals(generation.fusionConfigVersion())) {
            throw new KnowledgeIndexUnavailableException();
        }
        int candidateLimit = ReciprocalRankFusion.candidateLimit(query.limit());
        KnowledgeSearchCandidateRequest candidateRequest = new KnowledgeSearchCandidateRequest(
                generation, scope, query.filters(), candidateLimit);

        KnowledgeEmbeddingVector queryVector = requiresSemantic(query.mode())
                ? queryEmbedding(generation, query.query()) : null;
        List<KnowledgeSearchCandidate> keywordCandidates = query.mode() == KnowledgeSearchMode.SEMANTIC
                ? List.of() : keywords.findKeywordCandidates(candidateRequest, query.query());
        List<KnowledgeSearchCandidate> semanticCandidates = query.mode() == KnowledgeSearchMode.KEYWORD
                ? List.of() : semantics.findSemanticCandidates(candidateRequest, queryVector);
        LOGGER.info("knowledge_search_candidates completed traceId={} operation=knowledge_search generationId={} "
                        + "keywordCandidateCount={} semanticCandidateCount={} candidateLimit={}",
                traceId, generation.generationId(), keywordCandidates.size(), semanticCandidates.size(), candidateLimit);

        List<FusedKnowledgeSearchCandidate> fused = fusion.fuse(
                query.mode(), keywordCandidates, semanticCandidates, query.limit());
        List<Long> candidateIds = fused.stream().map(FusedKnowledgeSearchCandidate::documentId).toList();
        List<Long> eligibleIds = eligibility.retainEligible(candidateIds, scope);
        Set<Long> eligibleSet = new LinkedHashSet<>(eligibleIds);
        List<KnowledgeSearchResult> results = fused.stream()
                .filter(candidate -> eligibleSet.contains(candidate.documentId()))
                .map(candidate -> toResult(candidate, scope))
                .toList();
        LOGGER.info("knowledge_search_eligibility completed traceId={} operation=knowledge_search generationId={} "
                        + "candidateDocumentCount={} excludedCount={} eligibleCount={}",
                traceId, generation.generationId(), candidateIds.size(), candidateIds.size() - results.size(),
                results.size());

        List<KnowledgeSearchWarning> warnings = warnings(scope);
        KnowledgeSearchResponse response = new KnowledgeSearchResponse(
                new KnowledgeSearchContext(scope.contextType(), scope.projectIdentifier(), scope.branch()),
                query.mode(), generation.generationId(), warnings, results);
        LOGGER.info("knowledge_search completed traceId={} operation=knowledge_search generationId={} "
                        + "resultCount={} warningCount={} elapsedMs={} result=SUCCESS",
                traceId, generation.generationId(), results.size(), warnings.size(), elapsed(started));
        return response;
    }

    private ActiveKnowledgeSearchGeneration activeGeneration() {
        try {
            return generations.findActive().orElseThrow(KnowledgeIndexUnavailableException::new);
        } catch (KnowledgeIndexUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KnowledgeIndexUnavailableException(exception);
        }
    }

    private KnowledgeEmbeddingVector queryEmbedding(
            ActiveKnowledgeSearchGeneration generation,
            String query
    ) {
        try {
            KnowledgeEmbeddingModelDescriptor model = embedding.describeModel();
            if (!generation.modelId().equals(model.modelId())
                    || !generation.modelChecksum().equalsIgnoreCase(model.checksum())
                    || generation.vectorDimension() != model.dimension()) {
                throw new KnowledgeEmbeddingUnavailableException();
            }
            KnowledgeEmbeddingVector vector = embedding.embedQuery(query);
            if (vector == null || vector.dimension() != generation.vectorDimension()) {
                throw new KnowledgeEmbeddingUnavailableException();
            }
            return vector;
        } catch (KnowledgeEmbeddingUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KnowledgeEmbeddingUnavailableException(exception);
        }
    }

    private KnowledgeSearchResolvedScope resolvedScope(PreparedQuery query, KnowledgeBrowseContext browse) {
        if (browse.type() != query.contextType()) {
            throw new IllegalStateException("resolved knowledge search context does not match request");
        }
        if (browse.type() == KnowledgeBrowseContextType.GLOBAL) {
            return new KnowledgeSearchResolvedScope(browse.type(), null, null, null, null);
        }
        if (browse.projectId() == null || browse.branchId() == null) {
            throw new IllegalStateException("resolved project knowledge search scope is incomplete");
        }
        return new KnowledgeSearchResolvedScope(browse.type(), query.projectIdentifier(), query.actualBranch(),
                browse.projectId(), browse.branchId());
    }

    private KnowledgeSearchResult toResult(
            FusedKnowledgeSearchCandidate fused,
            KnowledgeSearchResolvedScope requestedScope
    ) {
        KnowledgeSearchCandidate candidate = fused.bestCandidate();
        KnowledgeSearchResultScope resultScope = switch (candidate.scope().type()) {
            case GLOBAL -> new KnowledgeSearchResultScope(KnowledgeScopeType.GLOBAL, null, null);
            case PROJECT -> new KnowledgeSearchResultScope(
                    KnowledgeScopeType.PROJECT, requestedScope.projectIdentifier(), null);
            case BRANCH -> new KnowledgeSearchResultScope(
                    KnowledgeScopeType.BRANCH, requestedScope.projectIdentifier(), requestedScope.branch());
        };
        return new KnowledgeSearchResult(
                fused.documentId(), resultScope, candidate.title(), fused.snippet(), fused.truncated(),
                candidate.format(), candidate.tags(), candidate.source(), candidate.sourceUpdatedAt(),
                fused.relevance(), fused.matchedBy());
    }

    private List<KnowledgeSearchWarning> warnings(KnowledgeSearchResolvedScope scope) {
        if (scope.contextType() == KnowledgeBrowseContextType.GLOBAL) {
            return List.of();
        }
        return codeSnapshots.get(scope.projectIdentifier(), scope.branch()).status()
                == CodeSnapshotAvailability.NOT_INDEXED
                ? List.of(KnowledgeSearchWarning.CODE_SNAPSHOT_NOT_INDEXED) : List.of();
    }

    private PreparedQuery prepare(KnowledgeSearchQuery query) {
        if (query == null || query.contextType() == null) {
            throw new IllegalArgumentException("knowledge search context is required");
        }
        String normalizedQuery = normalizeRequired(query.query(), "knowledge search query");
        if (codePoints(normalizedQuery) > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("knowledge search query exceeds limit");
        }
        String projectIdentifier = normalizeOptional(query.projectIdentifier());
        String requestedBranch = normalizeOptional(query.branch());
        if (query.contextType() == KnowledgeBrowseContextType.GLOBAL
                && (projectIdentifier != null || requestedBranch != null)) {
            throw new IllegalArgumentException("global knowledge search contains project scope");
        }
        if (query.contextType() == KnowledgeBrowseContextType.PROJECT && projectIdentifier == null) {
            throw new IllegalArgumentException("project knowledge search requires project identifier");
        }
        KnowledgeSearchMode mode = query.mode() == null ? KnowledgeSearchMode.HYBRID : query.mode();
        int limit = query.limit() == null ? DEFAULT_LIMIT : query.limit();
        ReciprocalRankFusion.candidateLimit(limit);
        KnowledgeSearchFilters filters = normalizedFilters(query.filters());
        String actualBranch = query.contextType() == KnowledgeBrowseContextType.PROJECT
                ? requestedBranch == null ? "main" : requestedBranch : null;
        return new PreparedQuery(query.contextType(), projectIdentifier, requestedBranch, actualBranch,
                normalizedQuery, mode, filters, limit);
    }

    private KnowledgeSearchFilters normalizedFilters(KnowledgeSearchFilters filters) {
        if (filters == null) {
            return new KnowledgeSearchFilters(List.of(), null, null);
        }
        if (filters.tags().size() > MAX_TAGS) {
            throw new IllegalArgumentException("knowledge search tag count exceeds limit");
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String tag : filters.tags()) {
            tags.add(normalizeRequired(tag, "knowledge search tag").toLowerCase(Locale.ROOT));
        }
        return new KnowledgeSearchFilters(List.copyOf(tags), filters.format(), filters.sourceType());
    }

    private boolean requiresSemantic(KnowledgeSearchMode mode) {
        return mode == KnowledgeSearchMode.SEMANTIC || mode == KnowledgeSearchMode.HYBRID;
    }

    private String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }

    private int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private String queryHash(String query) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? "none" : traceId;
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record PreparedQuery(
            KnowledgeBrowseContextType contextType,
            String projectIdentifier,
            String requestedBranch,
            String actualBranch,
            String query,
            KnowledgeSearchMode mode,
            KnowledgeSearchFilters filters,
            int limit
    ) {
    }
}
