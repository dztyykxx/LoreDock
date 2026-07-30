package io.github.loredock.knowledge.application.search;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.loredock.code.application.ActiveCodeSnapshotQueryUseCase;
import io.github.loredock.code.application.ActiveCodeSnapshotView;
import io.github.loredock.code.application.CodeSnapshotAvailability;
import io.github.loredock.knowledge.application.KnowledgeBrowseContext;
import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.KnowledgeScopeResolver;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchServiceTest {

    private static final UUID GENERATION_ID = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("61000000-0000-0000-0000-000000000002");
    private static final UUID BRANCH_ID = UUID.fromString("61000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    private KnowledgeScopeResolver scopes;
    private ActiveKnowledgeSearchGenerationReader generations;
    private KnowledgeKeywordCandidatePort keywords;
    private KnowledgeSemanticCandidatePort semantics;
    private KnowledgeEmbeddingPort embedding;
    private KnowledgeSearchEligibilityReader eligibility;
    private ActiveCodeSnapshotQueryUseCase codeSnapshots;
    private KnowledgeSearchService service;

    @BeforeEach
    void setUp() {
        scopes = mock(KnowledgeScopeResolver.class);
        generations = mock(ActiveKnowledgeSearchGenerationReader.class);
        keywords = mock(KnowledgeKeywordCandidatePort.class);
        semantics = mock(KnowledgeSemanticCandidatePort.class);
        embedding = mock(KnowledgeEmbeddingPort.class);
        eligibility = mock(KnowledgeSearchEligibilityReader.class);
        codeSnapshots = mock(ActiveCodeSnapshotQueryUseCase.class);
        service = new KnowledgeSearchService(scopes, generations, keywords, semantics, embedding,
                eligibility, codeSnapshots, new ReciprocalRankFusion());
        when(scopes.resolveBrowse(KnowledgeBrowseContextType.PROJECT, "project-a", null))
                .thenReturn(new KnowledgeBrowseContext(KnowledgeBrowseContextType.PROJECT, PROJECT_ID, BRANCH_ID));
        when(scopes.resolveBrowse(KnowledgeBrowseContextType.GLOBAL, null, null))
                .thenReturn(new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null));
        when(generations.findActive()).thenReturn(Optional.of(generation()));
        when(embedding.describeModel()).thenReturn(model());
        when(embedding.embedQuery(any())).thenReturn(vector(0));
        when(keywords.findCandidates(any(), any())).thenReturn(List.of());
        when(semantics.findCandidates(any(), any())).thenReturn(List.of());
        when(eligibility.retainEligible(any(), any())).thenAnswer(invocation -> {
            Collection<UUID> candidateIds = invocation.getArgument(0);
            return List.copyOf(candidateIds);
        });
        when(codeSnapshots.get("project-a", "main")).thenReturn(new ActiveCodeSnapshotView(
                "project-a", "main", CodeSnapshotAvailability.NOT_INDEXED,
                null, null, null, null, null));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /**
     * 业务目的：PROJECT 省略分支、模式和上限时必须固定解析 main、HYBRID 与候选 50，且所有过滤同时传给两路候选。
     */
    @Test
    void projectDefaultsAndFiltersAreFixedBeforeHybridCandidateDispatch() {
        UUID documentId = id(10);
        when(keywords.findCandidates(any(), any())).thenReturn(List.of(
                candidate(documentId, 0, 9, KnowledgeScope.branch(PROJECT_ID, BRANCH_ID))));
        when(semantics.findCandidates(any(), any())).thenReturn(List.of(
                candidate(documentId, 1, 0.9, KnowledgeScope.branch(PROJECT_ID, BRANCH_ID))));

        KnowledgeSearchResponse response = service.search(new KnowledgeSearchQuery(
                KnowledgeBrowseContextType.PROJECT, " project-a ", null, " 恢复方案 ", null,
                new KnowledgeSearchFilters(List.of("API", "恢复"), DocumentFormat.MARKDOWN,
                        DocumentSourceType.WIKI), null));

        ArgumentCaptor<KnowledgeSearchCandidateRequest> requests =
                ArgumentCaptor.forClass(KnowledgeSearchCandidateRequest.class);
        verify(keywords).findCandidates(requests.capture(), org.mockito.ArgumentMatchers.eq("恢复方案"));
        verify(semantics).findCandidates(requests.capture(), any(KnowledgeEmbeddingVector.class));
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.generation().generationId()).isEqualTo(GENERATION_ID);
            assertThat(request.scope()).isEqualTo(new KnowledgeSearchResolvedScope(
                    KnowledgeBrowseContextType.PROJECT, "project-a", "main", PROJECT_ID, BRANCH_ID));
            assertThat(request.filters().tags()).containsExactly("api", "恢复");
            assertThat(request.candidateLimit()).isEqualTo(50);
        });
        assertThat(response.context()).isEqualTo(new KnowledgeSearchContext(
                KnowledgeBrowseContextType.PROJECT, "project-a", "main"));
        assertThat(response.mode()).isEqualTo(KnowledgeSearchMode.HYBRID);
        assertThat(response.generationId()).isEqualTo(GENERATION_ID);
        assertThat(response.warnings()).containsExactly(KnowledgeSearchWarning.CODE_SNAPSHOT_NOT_INDEXED);
        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.documentId()).isEqualTo(documentId);
            assertThat(result.matchedBy()).isEqualTo(KnowledgeSearchMatchedBy.BOTH);
            assertThat(result.scope().projectIdentifier()).isEqualTo("project-a");
        });
        System.out.printf("测试证据：场景=项目混合搜索默认值，分支=%s，mode=%s，candidateLimit=%d，结果数=%d，警告=%s%n",
                response.context().branch(), response.mode(), requests.getAllValues().getFirst().candidateLimit(),
                response.results().size(), response.warnings());
    }

    /**
     * 业务目的：KEYWORD 模式在模型不可用时仍必须只调用关键词端口，不能初始化或静默依赖 Embedding。
     */
    @Test
    void keywordModeNeverDependsOnEmbeddingRuntime() {
        UUID documentId = id(20);
        when(keywords.findCandidates(any(), any())).thenReturn(List.of(candidate(documentId, 0, 5)));
        when(embedding.describeModel()).thenThrow(new KnowledgeEmbeddingUnavailableException());

        KnowledgeSearchResponse response = service.search(globalQuery(KnowledgeSearchMode.KEYWORD, 10));

        assertThat(response.results()).singleElement()
                .extracting(KnowledgeSearchResult::matchedBy).isEqualTo(KnowledgeSearchMatchedBy.KEYWORD);
        verify(embedding, never()).describeModel();
        verify(embedding, never()).embedQuery(any());
        verify(semantics, never()).findCandidates(any(), any());
        System.out.printf("测试证据：场景=纯关键词不依赖模型，generation=%s，关键词结果数=%d，Embedding调用=0%n",
                response.generationId(), response.results().size());
    }

    /**
     * 业务目的：SEMANTIC 模式必须校验 generation 模型后生成查询向量，且不能执行关键词候选。
     */
    @Test
    void semanticModeUsesMatchingGenerationModelWithoutKeywordCandidates() {
        UUID documentId = id(30);
        when(semantics.findCandidates(any(), any())).thenReturn(List.of(candidate(documentId, 0, 0.95)));

        KnowledgeSearchResponse response = service.search(globalQuery(KnowledgeSearchMode.SEMANTIC, 5));

        verify(embedding).describeModel();
        verify(embedding).embedQuery("恢复方案");
        verify(keywords, never()).findCandidates(any(), any());
        assertThat(response.results()).singleElement()
                .extracting(KnowledgeSearchResult::matchedBy).isEqualTo(KnowledgeSearchMatchedBy.SEMANTIC);
        System.out.printf("测试证据：场景=纯语义调度，generation=%s，模型=%s，结果数=%d%n",
                response.generationId(), model().modelId(), response.results().size());
    }

    /**
     * 业务目的：没有完整活动搜索 generation 时必须明确 503，不能把尚未索引伪装为空结果或尝试候选查询。
     */
    @Test
    void missingActiveGenerationFailsBeforeCandidateLookup() {
        when(generations.findActive()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.search(globalQuery(KnowledgeSearchMode.HYBRID, 10)))
                .isInstanceOf(KnowledgeIndexUnavailableException.class);
        verify(keywords, never()).findCandidates(any(), any());
        verify(semantics, never()).findCandidates(any(), any());
        System.out.println("测试证据：场景=无活动搜索generation，错误码=KNOWLEDGE_INDEX_UNAVAILABLE，候选调用=0");
    }

    /**
     * 业务目的：SEMANTIC/HYBRID 的本地模型标识、checksum 或维度与 generation 不一致时必须明确失败，不能退化成关键词结果。
     */
    @Test
    void mismatchedEmbeddingModelFailsWithoutPartialHybridSearch() {
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "wrong-model", "c".repeat(64), 512));

        assertThatThrownBy(() -> service.search(globalQuery(KnowledgeSearchMode.HYBRID, 10)))
                .isInstanceOf(KnowledgeEmbeddingUnavailableException.class);
        verify(keywords, never()).findCandidates(any(), any());
        verify(semantics, never()).findCandidates(any(), any());
        System.out.println("测试证据：场景=查询模型与generation不匹配，错误码=KNOWLEDGE_EMBEDDING_UNAVAILABLE，降级=false");
    }

    /**
     * 业务目的：实时资格复核移除已归档或改范围文档后不得从候选上限之外补足，避免二次查询扩大或改变固定范围。
     */
    @Test
    void realtimeEligibilityRemovalDoesNotBackfillFromAdditionalCandidates() {
        UUID archived = id(40);
        UUID eligibleId = id(41);
        UUID outsideLimit = id(42);
        when(keywords.findCandidates(any(), any())).thenReturn(List.of(
                candidate(archived, 0, 9), candidate(eligibleId, 0, 8), candidate(outsideLimit, 0, 7)));
        doReturn(List.of(eligibleId)).when(eligibility).retainEligible(any(), any());

        KnowledgeSearchResponse response = service.search(globalQuery(KnowledgeSearchMode.KEYWORD, 2));

        assertThat(response.results()).extracting(KnowledgeSearchResult::documentId).containsExactly(eligibleId);
        verify(keywords, times(1)).findCandidates(any(), any());
        verify(eligibility).retainEligible(List.of(archived, eligibleId),
                new KnowledgeSearchResolvedScope(KnowledgeBrowseContextType.GLOBAL, null, null, null, null));
        System.out.printf("测试证据：场景=实时资格删除不补足，融合前上限=2，排除文档=%s，返回=%s，候选查询次数=1%n",
                archived, response.results().stream().map(KnowledgeSearchResult::documentId).toList());
    }

    /**
     * 业务目的：搜索开始时读取的 generation 必须固定到请求结束，并发激活不得让两路候选混用新旧 generation。
     */
    @Test
    void oneRequestReadsAndUsesExactlyOneGeneration() {
        when(generations.findActive()).thenReturn(Optional.of(generation()), Optional.of(new ActiveKnowledgeSearchGeneration(
                id(99), model().modelId(), model().checksum(), 512, "cjk-v1", "rrf-v1", 1, 1, NOW)));

        KnowledgeSearchResponse response = service.search(globalQuery(KnowledgeSearchMode.HYBRID, 10));

        verify(generations, times(1)).findActive();
        ArgumentCaptor<KnowledgeSearchCandidateRequest> request =
                ArgumentCaptor.forClass(KnowledgeSearchCandidateRequest.class);
        verify(keywords).findCandidates(request.capture(), any());
        verify(semantics).findCandidates(request.capture(), any());
        assertThat(request.getAllValues()).extracting(value -> value.generation().generationId())
                .containsOnly(GENERATION_ID);
        assertThat(response.generationId()).isEqualTo(GENERATION_ID);
        System.out.printf("测试证据：场景=请求内固定generation，活动读取次数=1，两路generation=%s，响应generation=%s%n",
                request.getAllValues().stream().map(value -> value.generation().generationId()).toList(),
                response.generationId());
    }

    /**
     * 业务目的：合法范围没有候选时必须返回空列表，不得查询其他项目、其他分支或切换检索模式补结果。
     */
    @Test
    void emptyResultNeverBroadensScopeOrChangesMode() {
        KnowledgeSearchResponse response = service.search(globalQuery(KnowledgeSearchMode.KEYWORD, 10));

        assertThat(response.results()).isEmpty();
        verify(scopes).resolveBrowse(KnowledgeBrowseContextType.GLOBAL, null, null);
        verify(keywords, times(1)).findCandidates(any(), any());
        verify(semantics, never()).findCandidates(any(), any());
        System.out.printf("测试证据：场景=范围内无结果，context=%s，mode=%s，结果数=0，范围扩大=false%n",
                response.context().type(), response.mode());
    }

    /**
     * 业务目的：查询上限必须按 Unicode code point 而非 UTF-16 单元判断，且 GLOBAL 残留项目参数必须在范围查询前拒绝。
     */
    @Test
    void unicodeQueryLimitAndGlobalResidualScopeAreValidatedBeforeSearch() {
        String fiveHundredEmoji = "😀".repeat(500);
        KnowledgeSearchResponse accepted = service.search(new KnowledgeSearchQuery(
                KnowledgeBrowseContextType.GLOBAL, null, null, fiveHundredEmoji, KnowledgeSearchMode.KEYWORD,
                new KnowledgeSearchFilters(List.of(), null, null), 1));

        assertThat(accepted.results()).isEmpty();
        assertThatThrownBy(() -> service.search(new KnowledgeSearchQuery(
                KnowledgeBrowseContextType.GLOBAL, null, null, fiveHundredEmoji + "😀",
                KnowledgeSearchMode.KEYWORD, new KnowledgeSearchFilters(List.of(), null, null), 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new KnowledgeSearchQuery(
                KnowledgeBrowseContextType.GLOBAL, "residual-project", null, "恢复",
                KnowledgeSearchMode.KEYWORD, new KnowledgeSearchFilters(List.of(), null, null), 1)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(scopes, times(1)).resolveBrowse(KnowledgeBrowseContextType.GLOBAL, null, null);
        System.out.println("测试证据：场景=Unicode查询与GLOBAL残留校验，500码点通过，501码点拒绝，残留项目拒绝，越界查询=0");
    }

    /**
     * 业务目的：候选基础设施异常必须转换为安全索引 503，不能把 SQL、连接或原始异常语义交给调用方。
     */
    @Test
    void unexpectedCandidateFailureBecomesSafeIndexUnavailableError() {
        when(keywords.findCandidates(any(), any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://internal/token=secret"));

        assertThatThrownBy(() -> service.search(globalQuery(KnowledgeSearchMode.KEYWORD, 10)))
                .isInstanceOf(KnowledgeIndexUnavailableException.class)
                .hasMessageNotContaining("jdbc").hasMessageNotContaining("secret");
        System.out.println("测试证据：场景=候选基础设施异常，错误码=KNOWLEDGE_INDEX_UNAVAILABLE，对外诊断已隐藏");
    }

    /**
     * 业务目的：搜索日志必须证明开始、范围、两路候选、资格复核、完成和失败路径，且不能记录原始查询、正文、向量或内部异常。
     */
    @Test
    void structuredLogsContainExecutionEvidenceWithoutSensitiveSearchData() {
        MDC.put("traceId", "trace-search-test");
        String privateQuery = "内部恢复问题-绝不能进入日志";
        UUID documentId = id(60);
        when(keywords.findCandidates(any(), any())).thenReturn(List.of(candidate(documentId, 0, 9)));
        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeSearchService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.search(new KnowledgeSearchQuery(KnowledgeBrowseContextType.GLOBAL, null, null,
                    privateQuery, KnowledgeSearchMode.KEYWORD, new KnowledgeSearchFilters(List.of(), null, null), 10));
            reset(keywords);
            when(keywords.findCandidates(any(), any()))
                    .thenThrow(new IllegalStateException("private-vector=[1,2] /srv/model/secret.onnx"));
            assertThatThrownBy(() -> service.search(globalQuery(KnowledgeSearchMode.KEYWORD, 10)))
                    .isInstanceOf(KnowledgeIndexUnavailableException.class);

            String rendered = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(rendered).contains(
                    "knowledge_search started", "knowledge_search_scope resolved",
                    "knowledge_search_candidates completed", "knowledge_search_eligibility completed",
                    "knowledge_search completed", "knowledge_search failed",
                    "traceId=trace-search-test", "generationId=" + GENERATION_ID,
                    "keywordCandidateCount=1", "eligibleCount=1", "errorCode=KNOWLEDGE_INDEX_UNAVAILABLE");
            assertThat(rendered).doesNotContain(privateQuery, "恢复正文", "private-vector", "/srv/model", "secret.onnx");
            System.out.printf("测试证据：场景=搜索结构化日志，日志事件数=%d，traceId存在=true，原始查询/正文/向量/路径泄漏=0%n",
                    appender.list.size());
        } finally {
            logger.detachAppender(appender);
        }
    }

    private KnowledgeSearchQuery globalQuery(KnowledgeSearchMode mode, int limit) {
        return new KnowledgeSearchQuery(KnowledgeBrowseContextType.GLOBAL, null, null, "恢复方案", mode,
                new KnowledgeSearchFilters(List.of(), null, null), limit);
    }

    private ActiveKnowledgeSearchGeneration generation() {
        return new ActiveKnowledgeSearchGeneration(GENERATION_ID, model().modelId(), model().checksum(), 512,
                "cjk-v1", "rrf-v1", 3, 3, NOW);
    }

    private KnowledgeEmbeddingModelDescriptor model() {
        return new KnowledgeEmbeddingModelDescriptor("BAAI/bge-small-zh-v1.5", "b".repeat(64), 512);
    }

    private KnowledgeEmbeddingVector vector(int axis) {
        float[] vector = new float[512];
        vector[axis] = 1;
        return new KnowledgeEmbeddingVector(vector);
    }

    private KnowledgeSearchCandidate candidate(UUID documentId, int chunkNo, double score) {
        return candidate(documentId, chunkNo, score, KnowledgeScope.global());
    }

    private KnowledgeSearchCandidate candidate(
            UUID documentId,
            int chunkNo,
            double score,
            KnowledgeScope scope
    ) {
        return new KnowledgeSearchCandidate(documentId, chunkNo, 0, 4, "恢复正文", "恢复标题",
                List.of(DocumentTag.of("恢复")), new DocumentSource(DocumentSourceType.MANUAL, null, null, null),
                scope, DocumentFormat.MARKDOWN, NOW, score);
    }

    private UUID id(int suffix) {
        return UUID.fromString("62000000-0000-0000-0000-" + String.format("%012d", suffix));
    }
}
