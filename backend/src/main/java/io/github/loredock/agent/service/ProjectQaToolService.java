package io.github.loredock.agent.service;

import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.config.AgentRuntimeLimits;
import io.github.loredock.agent.exception.AgentToolException;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.AgentToolResult;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.model.snapshot.EvidenceSourceMetadata;
import io.github.loredock.agent.model.tool.CodeSearchToolRequest;
import io.github.loredock.agent.model.tool.CodeSnippetToolRequest;
import io.github.loredock.agent.model.tool.KnowledgeSearchToolRequest;
import io.github.loredock.code.model.enums.CodeSearchTarget;
import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.model.request.CodeSearchQuery;
import io.github.loredock.code.model.request.CodeSnippetQuery;
import io.github.loredock.code.model.result.ActiveCodeSnapshotView;
import io.github.loredock.code.service.ActiveCodeSnapshotQueryService;
import io.github.loredock.code.service.CodeSearchService;
import io.github.loredock.code.service.CodeSnippetService;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;
import io.github.loredock.knowledge.model.request.KnowledgeSearchFilters;
import io.github.loredock.knowledge.model.request.KnowledgeSearchQuery;
import io.github.loredock.knowledge.model.result.KnowledgeSearchResult;
import io.github.loredock.knowledge.service.KnowledgeSearchIndexDataService;
import io.github.loredock.knowledge.service.search.KnowledgeSearchService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 三个 project_qa 工具的应用实现：从运行事实读取固定范围，复用现有搜索用例并裁剪不可信正文。
 */
@Service
@Slf4j
public class ProjectQaToolService {

    private static final int MAX_SNIPPET_LINES = 200;
    private final AgentRunService runs;
    private final KnowledgeSearchService knowledge;
    private final KnowledgeSearchIndexDataService generations;
    private final CodeSearchService codeSearch;
    private final CodeSnippetService snippets;
    private final ActiveCodeSnapshotQueryService codeSnapshots;
    private final AgentProperties configuration;
    private final AgentEvidenceService evidence;
    private final AgentEventService events;
    private final Clock timeProvider;

    /**
     * @param runs 运行固定范围事实
     * @param knowledge 已有三层已发布知识混合检索
     * @param generations 活动知识 generation 读取端口
     * @param codeSearch 已有活动快照代码搜索
     * @param snippets 已有活动快照片段读取
     * @param codeSnapshots 活动代码快照状态
     * @param configuration 服务端工具上限和最低相关度
     * @param evidence 运行证据即时持久化端口
     * @param events 已提交公开事件端口
     * @param timeProvider UTC 时间源
     */
    public ProjectQaToolService(
            AgentRunService runs,
            KnowledgeSearchService knowledge,
            KnowledgeSearchIndexDataService generations,
            CodeSearchService codeSearch,
            CodeSnippetService snippets,
            ActiveCodeSnapshotQueryService codeSnapshots,
            AgentProperties configuration,
            AgentEvidenceService evidence,
            AgentEventService events,
            Clock timeProvider
    ) {
        this.runs = runs;
        this.knowledge = knowledge;
        this.generations = generations;
        this.codeSearch = codeSearch;
        this.snippets = snippets;
        this.codeSnapshots = codeSnapshots;
        this.configuration = configuration;
        this.evidence = evidence;
        this.events = events;
        this.timeProvider = timeProvider;
    }

    public AgentToolResult knowledgeSearch(Long runId, KnowledgeSearchToolRequest request) {
        return recorded(runId, "knowledge_search", () -> executeKnowledgeSearch(runId, request));
    }

    private AgentToolResult executeKnowledgeSearch(Long runId, KnowledgeSearchToolRequest request) {
        AgentRunSnapshot run = running(runId);
        Objects.requireNonNull(request, "request");
        if (run.scope().knowledgeGenerationId() == null) {
            return empty("knowledge_search", run);
        }
        requireKnowledgeVersion(run);
        int limit = boundedLimit(request.limit());
        var response = knowledge.search(new KnowledgeSearchQuery(
                KnowledgeBrowseContextType.PROJECT, run.scope().projectIdentifier(), run.scope().branch(),
                request.query(), KnowledgeSearchMode.HYBRID, new KnowledgeSearchFilters(List.of(), null, null), limit));
        requireKnowledgeVersion(run);
        if (!run.scope().knowledgeGenerationId().equals(response.generationId())) {
            throw versionChanged();
        }

        List<EvidenceContent> values = new ArrayList<>();
        for (KnowledgeSearchResult result : response.results()) {
            requireKnowledgeScope(run, result);
            boolean relevant = result.relevance() >= configuration.minimumRelevance();
            values.add(new EvidenceContent(knowledgeEvidence(run, result, relevant), result.snippet(), relevant,
                    "title=" + safeLine(result.title())));
        }
        AgentToolResult result = boundedContext(values);
        log.info("agent_tool completed runId={} tool=knowledge_search project={} branch={} generationId={} "
                        + "resultCount={} evidenceCount={} trimmedCharacters={}",
                runId, run.scope().projectIdentifier(), run.scope().branch(), response.generationId(),
                result.resultCount(), result.evidence().size(), result.trimmedCharacterCount());
        return result;
    }

    public AgentToolResult codeSearch(Long runId, CodeSearchToolRequest request) {
        return recorded(runId, "code_search", () -> executeCodeSearch(runId, request));
    }

    private AgentToolResult executeCodeSearch(Long runId, CodeSearchToolRequest request) {
        AgentRunSnapshot run = running(runId);
        Objects.requireNonNull(request, "request");
        requireCodeVersion(run);
        if (!run.scope().hasCodeSnapshot()) {
            return empty("code_search", run);
        }
        int limit = boundedLimit(request.limit());
        var response = codeSearch.search(new CodeSearchQuery(
                run.scope().projectIdentifier(), run.scope().branch(), request.query(), CodeSearchTarget.ALL,
                request.pathPrefix(), limit));
        requireCodeVersion(run);
        List<EvidenceContent> values = response.items().stream().map(value -> {
            requireCodeSource(run, value.snapshotId(), value.commit(), value.projectIdentifier(), value.branch());
            AgentEvidence evidence = codeEvidence(run, value.path(), value.indexedAt(), clamp(value.score()));
            return new EvidenceContent(evidence, value.snippet(), true, "path=" + safeLine(value.path()));
        }).toList();
        AgentToolResult result = boundedContext(values);
        log.info("agent_tool completed runId={} tool=code_search project={} branch={} snapshotId={} commit={} "
                        + "resultCount={} evidenceCount={} trimmedCharacters={}",
                runId, run.scope().projectIdentifier(), run.scope().branch(), run.scope().snapshotId(),
                run.scope().commit(), result.resultCount(), result.evidence().size(), result.trimmedCharacterCount());
        return result;
    }

    public AgentToolResult codeSnippetRead(Long runId, CodeSnippetToolRequest request) {
        return recorded(runId, "code_snippet_read", () -> executeCodeSnippetRead(runId, request));
    }

    private AgentToolResult executeCodeSnippetRead(Long runId, CodeSnippetToolRequest request) {
        AgentRunSnapshot run = running(runId);
        Objects.requireNonNull(request, "request");
        requireCodeVersion(run);
        if (!run.scope().hasCodeSnapshot()) {
            return empty("code_snippet_read", run);
        }
        int lineCount = request.lineCount() == null ? 80 : Math.min(request.lineCount(), MAX_SNIPPET_LINES);
        var response = snippets.read(new CodeSnippetQuery(
                run.scope().projectIdentifier(), run.scope().branch(), request.repositoryPath(),
                request.startLine(), lineCount));
        requireCodeVersion(run);
        requireCodeSource(run, response.snapshotId(), response.commit(),
                response.projectIdentifier(), response.branch());
        AgentEvidence evidence = codeEvidence(run, response.path(), response.indexedAt(), 1.0);
        AgentToolResult result = boundedContext(List.of(new EvidenceContent(
                evidence, response.content(), true,
                "path=" + safeLine(response.path()) + " lines=" + response.startLine() + "-" + response.endLine())));
        log.info("agent_tool completed runId={} tool=code_snippet_read project={} branch={} snapshotId={} commit={} "
                        + "resultCount={} evidenceCount={} trimmedCharacters={}",
                runId, run.scope().projectIdentifier(), run.scope().branch(), run.scope().snapshotId(),
                run.scope().commit(), result.resultCount(), result.evidence().size(), result.trimmedCharacterCount());
        return result;
    }

    private AgentRunSnapshot running(Long runId) {
        AgentRunSnapshot run = runs.findById(runId).orElseThrow(
                () -> new AgentToolException(AgentErrorCode.AGENT_TOOL_SCOPE_VIOLATION));
        if (run.status() != AgentRunStatus.RUNNING) {
            throw new AgentToolException(AgentErrorCode.AGENT_TOOL_SCOPE_VIOLATION);
        }
        return run;
    }

    private AgentToolResult recorded(
            Long runId,
            String toolName,
            Supplier<AgentToolResult> action
    ) {
        AgentToolResult result = action.get();
        // 工具内部步骤由框架观察和日志承担；数据库只保存有限来源与对外有意义的来源发现事件。
        evidence.saveAll(runId, result.evidence());
        if (result.resultCount() > 0) {
            events.append(runId, AgentEventType.SOURCE_FOUND,
                    toolName + " count=" + result.resultCount(), timeProvider.instant());
        }
        return result;
    }

    private void requireKnowledgeVersion(AgentRunSnapshot run) {
        Long active = generations.findActive().map(value -> value.generationId()).orElse(null);
        if (!Objects.equals(run.scope().knowledgeGenerationId(), active)) {
            throw versionChanged();
        }
    }

    private void requireCodeVersion(AgentRunSnapshot run) {
        ActiveCodeSnapshotView active = codeSnapshots.get(
                run.scope().projectIdentifier(), run.scope().branch());
        Long snapshotId = active.status() == CodeSnapshotAvailability.INDEXED ? active.snapshotId() : null;
        String commit = active.status() == CodeSnapshotAvailability.INDEXED ? active.commit() : null;
        if (!Objects.equals(run.scope().snapshotId(), snapshotId)
                || !Objects.equals(run.scope().commit(), commit)) {
            throw versionChanged();
        }
    }

    private void requireKnowledgeScope(AgentRunSnapshot run, KnowledgeSearchResult result) {
        String type = result.scope().type().name();
        boolean allowed = run.scope().allowedKnowledgeScopes().contains(type);
        boolean projectMatches = result.scope().projectIdentifier() == null
                || run.scope().projectIdentifier().equals(result.scope().projectIdentifier());
        boolean branchMatches = result.scope().branch() == null
                || run.scope().branch().equals(result.scope().branch());
        if (!allowed || !projectMatches || !branchMatches) {
            throw new AgentToolException(AgentErrorCode.AGENT_TOOL_SCOPE_VIOLATION);
        }
    }

    private void requireCodeSource(
            AgentRunSnapshot run,
            Long snapshotId,
            String commit,
            String project,
            String branch
    ) {
        if (!Objects.equals(run.scope().snapshotId(), snapshotId)
                || !Objects.equals(run.scope().commit(), commit)
                || !run.scope().projectIdentifier().equals(project)
                || !run.scope().branch().equals(branch)) {
            throw new AgentToolException(AgentErrorCode.AGENT_TOOL_SCOPE_VIOLATION);
        }
    }

    private AgentEvidence knowledgeEvidence(AgentRunSnapshot run, KnowledgeSearchResult result, boolean retained) {
        var source = result.source();
        EvidenceSourceMetadata metadata = source == null
                ? EvidenceSourceMetadata.historicalUnknown()
                : new EvidenceSourceMetadata(
                        EvidenceSourceMetadata.CURRENT_SCHEMA_VERSION,
                        result.scope().type().name(), source.type().name(), source.wikiUrl(), source.originalFilename());
        return new AgentEvidence(null, run.runId(), EvidenceSourceType.KNOWLEDGE, retained,
                result.relevance(), result.documentId(), null, run.scope().projectIdentifier(), run.scope().branch(),
                null, null, result.title(), result.sourceUpdatedAt(), metadata);
    }

    private AgentEvidence codeEvidence(AgentRunSnapshot run, String path, Instant indexedAt, double relevance) {
        return new AgentEvidence(null, run.runId(), EvidenceSourceType.CODE, true, relevance,
                null, run.scope().snapshotId(), run.scope().projectIdentifier(), run.scope().branch(),
                run.scope().commit(), path, null, indexedAt);
    }

    private AgentToolResult boundedContext(List<EvidenceContent> input) {
        AgentRuntimeLimits limits = configuration.runtimeLimits();
        int contextLimit = limits.maxContextCharacters();
        int snippetLimit = limits.maxSnippetCharacters();
        StringBuilder context = new StringBuilder();
        List<AgentEvidence> evidence = new ArrayList<>();
        int trimmed = 0;
        int retainedCount = 0;
        for (EvidenceContent item : input) {
            String original = item.content() == null ? "" : item.content();
            String snippet = truncate(original, snippetLimit);
            trimmed += codePoints(original) - codePoints(snippet);
            boolean retain = item.candidate() && retainedCount < limits.maxResultsPerTool();
            String block = block(item.evidence().id(), item.header(), snippet);
            if (retain && codePoints(context.toString()) + codePoints(block) <= contextLimit) {
                context.append(block);
                retainedCount++;
            } else {
                retain = false;
                trimmed += codePoints(snippet);
            }
            AgentEvidence value = item.evidence();
            evidence.add(new AgentEvidence(value.id(), value.runId(), value.sourceType(), retain,
                    value.relevance(), value.documentId(), value.snapshotId(), value.projectIdentifier(),
                    value.branch(), value.commit(), value.repositoryPath(), value.title(), value.sourceUpdatedAt(),
                    value.sourceMetadata()));
        }
        return new AgentToolResult(context.toString(), evidence, retainedCount, trimmed);
    }

    private String block(Long evidenceId, String header, String content) {
        return "UNTRUSTED_EVIDENCE_BEGIN\n"
                + "evidenceId=" + evidenceId + "\n"
                + header + "\n"
                + "content=" + content + "\n"
                + "UNTRUSTED_EVIDENCE_END\n";
    }

    private AgentToolResult empty(String tool, AgentRunSnapshot run) {
        log.info("agent_tool completed runId={} tool={} project={} branch={} resultCount=0 evidenceCount=0",
                run.runId(), tool, run.scope().projectIdentifier(), run.scope().branch());
        return new AgentToolResult("", List.of(), 0, 0);
    }

    private int boundedLimit(Integer requested) {
        int maximum = configuration.runtimeLimits().maxResultsPerTool();
        if (requested == null) {
            return maximum;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("tool result limit invalid");
        }
        return Math.min(requested, maximum);
    }

    private String truncate(String value, int maximum) {
        int count = codePoints(value);
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private String safeLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private double clamp(float value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private AgentToolException versionChanged() {
        return new AgentToolException(AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED);
    }

    private record EvidenceContent(AgentEvidence evidence, String content, boolean candidate, String header) {
    }
}
