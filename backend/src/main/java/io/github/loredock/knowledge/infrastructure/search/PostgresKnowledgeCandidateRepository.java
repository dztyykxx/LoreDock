package io.github.loredock.knowledge.infrastructure.search;

import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingVector;
import io.github.loredock.knowledge.application.search.KnowledgeKeywordCandidatePort;
import io.github.loredock.knowledge.application.search.KnowledgeSearchCandidate;
import io.github.loredock.knowledge.application.search.KnowledgeSearchCandidateRequest;
import io.github.loredock.knowledge.application.search.KnowledgeSearchFilters;
import io.github.loredock.knowledge.application.search.KnowledgeSearchResolvedScope;
import io.github.loredock.knowledge.application.search.KnowledgeSemanticCandidatePort;
import io.github.loredock.knowledge.application.search.ReciprocalRankFusion;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeTextAnalyzer;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeSearchCandidateMapper;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeSearchCandidateRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * PostgreSQL 候选适配器：关键词使用统一 CJK 分析与受限短词回退，语义使用精确余弦距离。
 * 两条路径都在数据库排序前应用固定 generation、业务范围、全部过滤条件和服务端上限。
 */
@Repository
public class PostgresKnowledgeCandidateRepository
        implements KnowledgeKeywordCandidatePort, KnowledgeSemanticCandidatePort {

    private static final int VECTOR_DIMENSION = 512;

    private final KnowledgeSearchCandidateMapper mapper;
    private final KnowledgeTextAnalyzer analyzer;
    private final ObjectMapper objectMapper;

    /**
     * @param mapper 参数化 PostgreSQL 候选 Mapper
     * @param analyzer 与索引构建一致的 CJK 分析器
     * @param objectMapper 投影标签解析器
     */
    public PostgresKnowledgeCandidateRepository(
            KnowledgeSearchCandidateMapper mapper,
            KnowledgeTextAnalyzer analyzer,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.analyzer = analyzer;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeSearchCandidate> findCandidates(
            KnowledgeSearchCandidateRequest request,
            String normalizedQuery
    ) {
        validateRequest(request);
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            throw new IllegalArgumentException("knowledge keyword query is required");
        }
        List<String> terms = analyzer.analyzeQuery(normalizedQuery);
        boolean literalFallback = terms.isEmpty();
        String tsQuery = terms.stream()
                .map(this::quotedTsLexeme)
                .collect(java.util.stream.Collectors.joining(" | "));
        return mapper.findKeywordCandidates(
                        request.generation().generationId(), request.scope().contextType().name(),
                        request.scope().projectId(), request.scope().branchId(), tags(request.filters()),
                        name(request.filters().format()), name(request.filters().sourceType()),
                        tsQuery, normalizedQuery, literalFallback, request.candidateLimit())
                .stream().map(this::toCandidate).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeSearchCandidate> findCandidates(
            KnowledgeSearchCandidateRequest request,
            KnowledgeEmbeddingVector queryEmbedding
    ) {
        validateRequest(request);
        if (queryEmbedding == null || queryEmbedding.dimension() != VECTOR_DIMENSION) {
            throw new IllegalArgumentException("knowledge query embedding dimension must be 512");
        }
        return mapper.findSemanticCandidates(
                        request.generation().generationId(), request.scope().contextType().name(),
                        request.scope().projectId(), request.scope().branchId(), tags(request.filters()),
                        name(request.filters().format()), name(request.filters().sourceType()),
                        vectorLiteral(queryEmbedding.values()), request.candidateLimit())
                .stream().map(this::toCandidate).toList();
    }

    private void validateRequest(KnowledgeSearchCandidateRequest request) {
        if (request == null || request.generation() == null || request.scope() == null
                || request.filters() == null || request.candidateLimit() < 1
                || request.candidateLimit() > ReciprocalRankFusion.MAX_CANDIDATE_LIMIT) {
            throw new IllegalArgumentException("knowledge candidate request is invalid");
        }
        KnowledgeSearchResolvedScope scope = request.scope();
        boolean validScope = scope.contextType() == KnowledgeBrowseContextType.GLOBAL
                ? scope.projectId() == null && scope.branchId() == null
                : scope.contextType() == KnowledgeBrowseContextType.PROJECT
                && scope.projectId() != null && scope.branchId() != null;
        if (!validScope) {
            throw new IllegalArgumentException("knowledge candidate scope is invalid");
        }
    }

    private String[] tags(KnowledgeSearchFilters filters) {
        return filters.tags().toArray(String[]::new);
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String quotedTsLexeme(String term) {
        // 词项来自服务端 Lucene 分析器，仍通过 SQL 参数绑定；引号转义防止词项改变 OR 表达式结构。
        return "'" + term.replace("'", "''") + "'";
    }

    private String vectorLiteral(float[] values) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            float value = values[index];
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("knowledge query embedding must contain finite values");
            }
            if (index > 0) {
                literal.append(',');
            }
            literal.append(value);
        }
        return literal.append(']').toString();
    }

    private KnowledgeSearchCandidate toCandidate(KnowledgeSearchCandidateRow row) {
        return new KnowledgeSearchCandidate(
                row.getDocumentId(), row.getChunkNo(), row.getStartOffset(), row.getEndOffset(),
                row.getContent(), row.getTitle(), tags(row.getTagsJson()),
                new DocumentSource(DocumentSourceType.valueOf(row.getSourceType()),
                        row.getWikiUrl(), row.getOriginalFilename(), row.getCurationNote()),
                scope(row), DocumentFormat.valueOf(row.getFormat()), row.getSourceUpdatedAt(), row.getRawScore());
    }

    private KnowledgeScope scope(KnowledgeSearchCandidateRow row) {
        return switch (KnowledgeScopeType.valueOf(row.getScopeType())) {
            case GLOBAL -> KnowledgeScope.global();
            case PROJECT -> KnowledgeScope.project(row.getProjectId());
            case BRANCH -> KnowledgeScope.branch(row.getProjectId(), row.getBranchId());
        };
    }

    private List<DocumentTag> tags(String json) {
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            return values.stream().map(DocumentTag::of).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("knowledge candidate tags are invalid", exception);
        }
    }
}
