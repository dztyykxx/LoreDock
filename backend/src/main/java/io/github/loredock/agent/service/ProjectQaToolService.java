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
import io.github.loredock.agent.model.tool.KnowledgeSearchToolRequest;
import io.github.loredock.knowledge.api.KnowledgeMatch;
import io.github.loredock.knowledge.api.KnowledgeMatches;
import io.github.loredock.knowledge.api.KnowledgeQuery;
import io.github.loredock.knowledge.api.KnowledgeSearchService;
import io.github.loredock.knowledge.api.KnowledgeSearchVersionChangedException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * project_qa 知识工具实现：从运行事实读取固定范围，复用知识搜索用例并裁剪不可信正文。
 */
@Service
@Slf4j
public class ProjectQaToolService {

    private final AgentRunService runs;
    private final KnowledgeSearchService knowledge;
    private final AgentProperties configuration;
    private final AgentEvidenceService evidence;
    private final AgentEventService events;
    private final Clock timeProvider;

    /**
     * @param runs 运行固定范围事实
     * @param knowledge 已有三层已发布知识混合检索
     * @param configuration 服务端工具上限和最低相关度
     * @param evidence 运行证据即时持久化端口
     * @param events 已提交公开事件端口
     * @param timeProvider UTC 时间源
     */
    public ProjectQaToolService(
            AgentRunService runs,
            KnowledgeSearchService knowledge,
            AgentProperties configuration,
            AgentEvidenceService evidence,
            AgentEventService events,
            Clock timeProvider
    ) {
        this.runs = runs;
        this.knowledge = knowledge;
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
        KnowledgeMatches response;
        try {
            response = knowledge.search(new KnowledgeQuery(
                    run.scope().projectIdentifier(), run.scope().branch(), request.query(), limit,
                    run.scope().knowledgeGenerationId()));
        } catch (KnowledgeSearchVersionChangedException exception) {
            throw versionChanged();
        }
        requireKnowledgeVersion(run);

        List<EvidenceContent> values = new ArrayList<>();
        for (KnowledgeMatch result : response.results()) {
            requireKnowledgeScope(run, result);
            boolean relevant = result.relevance() >= configuration.minimumRelevance();
            values.add(new EvidenceContent(knowledgeEvidence(run, result, relevant), result.snippet(), relevant,
                    "title=" + safeLine(result.title())));
        }
        AgentToolResult result = boundedContext(runId, values);
        log.info("agent_tool completed runId={} tool=knowledge_search project={} branch={} indexVersionId={} "
                        + "resultCount={} evidenceCount={} trimmedCharacters={}",
                runId, run.scope().projectIdentifier(), run.scope().branch(), run.scope().knowledgeGenerationId(),
                result.resultCount(), result.evidence().size(), result.trimmedCharacterCount());
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
        // 工具内部步骤由框架观察和日志承担；证据落库在 boundedContext 内完成，
        // 这里只追加对外有意义的来源发现事件。
        if (result.resultCount() > 0) {
            events.append(runId, AgentEventType.SOURCE_FOUND,
                    toolName + " count=" + result.resultCount(), timeProvider.instant());
        }
        return result;
    }

    private void requireKnowledgeVersion(AgentRunSnapshot run) {
        if (!knowledge.isActiveIndexVersion(run.scope().knowledgeGenerationId())) {
            throw versionChanged();
        }
    }

    private void requireKnowledgeScope(AgentRunSnapshot run, KnowledgeMatch result) {
        String type = result.scope().type();
        boolean allowed = run.scope().allowedKnowledgeScopes().contains(type);
        boolean projectMatches = result.scope().projectIdentifier() == null
                || run.scope().projectIdentifier().equals(result.scope().projectIdentifier());
        boolean branchMatches = result.scope().branch() == null
                || run.scope().branch().equals(result.scope().branch());
        if (!allowed || !projectMatches || !branchMatches) {
            throw new AgentToolException(AgentErrorCode.AGENT_TOOL_SCOPE_VIOLATION);
        }
    }

    private AgentEvidence knowledgeEvidence(AgentRunSnapshot run, KnowledgeMatch result, boolean retained) {
        var source = result.source();
        EvidenceSourceMetadata metadata = source == null
                ? EvidenceSourceMetadata.historicalUnknown()
                : new EvidenceSourceMetadata(
                        EvidenceSourceMetadata.CURRENT_SCHEMA_VERSION,
                        result.scope().type(), source.type(), source.wikiUrl(), source.originalFilename());
        return new AgentEvidence(null, run.runId(), EvidenceSourceType.KNOWLEDGE, retained,
                result.relevance(), result.documentId(), null, run.scope().projectIdentifier(), run.scope().branch(),
                null, null, result.title(), result.sourceUpdatedAt(), metadata);
    }

    private AgentToolResult boundedContext(Long runId, List<EvidenceContent> input) {
        AgentRuntimeLimits limits = configuration.runtimeLimits();
        int contextLimit = limits.maxContextCharacters();
        int snippetLimit = limits.maxSnippetCharacters();
        List<EvidenceContent> drafts = new ArrayList<>(input.size());
        List<AgentEvidence> pending = new ArrayList<>(input.size());
        int trimmed = 0;
        int retainedCount = 0;
        StringBuilder provisional = new StringBuilder();
        for (EvidenceContent item : input) {
            String original = item.content() == null ? "" : item.content();
            String snippet = truncate(original, snippetLimit);
            trimmed += codePoints(original) - codePoints(snippet);
            // 先用占位 ID 估算块长决定是否保留，保持与旧行为一致的裁剪边界；
            // 真实证据 ID 在落库后替换进上下文，模型才能引用真实来源。
            String placeholder = block(null, item.header(), snippet);
            boolean retain = item.candidate() && retainedCount < limits.maxResultsPerTool()
                    && codePoints(provisional.toString()) + codePoints(placeholder) <= contextLimit;
            if (retain) {
                provisional.append(placeholder);
                retainedCount++;
            } else {
                retain = false;
                trimmed += codePoints(snippet);
            }
            AgentEvidence value = item.evidence();
            pending.add(new AgentEvidence(value.id(), value.runId(), value.sourceType(), retain,
                    value.relevance(), value.documentId(), value.snapshotId(), value.projectIdentifier(),
                    value.branch(), value.commit(), value.repositoryPath(), value.title(), value.sourceUpdatedAt(),
                    value.sourceMetadata()));
            drafts.add(new EvidenceContent(value, snippet, retain, item.header()));
        }
        // 先落库取得数据库生成的证据 ID，再以真实 ID 重建模型上下文，保证模型可以引用真实证据。
        List<AgentEvidence> saved = evidence.saveAll(runId, pending);
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < saved.size(); index++) {
            AgentEvidence value = saved.get(index);
            if (value.retained()) {
                context.append(block(value.id(), drafts.get(index).header(), drafts.get(index).content()));
            }
        }
        return new AgentToolResult(context.toString(), saved, retainedCount, trimmed);
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

    private AgentToolException versionChanged() {
        return new AgentToolException(AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED);
    }

    private record EvidenceContent(AgentEvidence evidence, String content, boolean candidate, String header) {
    }
}
