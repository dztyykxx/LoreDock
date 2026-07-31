package io.github.loredock.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.loredock.agent.exception.AgentRunNotFoundException;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.model.command.AgentRunCreateData;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import io.github.loredock.agent.model.result.AgentRunAcceptanceResult;
import io.github.loredock.agent.model.result.TrustedProjectQaResult;
import io.github.loredock.agent.model.snapshot.AgentCitationSnapshot;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 运行服务。直接使用 Mapper 完成查询和状态变化；完成 CAS 与引用写入共享短事务，
 * 模型和工具等待永远发生在事务外。
 */
@Service
@Slf4j
public class AgentRunService {

    private final AgentRunMapper runs;
    private final AgentEvidenceService evidence;
    private final AgentEventService events;

    /** @param runs 运行 Mapper @param evidence 证据持久化服务 @param events 公开事件服务 */
    public AgentRunService(
            AgentRunMapper runs,
            AgentEvidenceService evidence,
            AgentEventService events
    ) {
        this.runs = runs;
        this.evidence = evidence;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Optional<AgentRunSnapshot> findByOperatorAndIdempotencyKey(String operatorId, String idempotencyKey) {
        return findEntityByOperatorAndIdempotencyKey(operatorId, idempotencyKey).map(this::snapshot);
    }

    @Transactional(readOnly = true)
    public Optional<AgentRunSnapshot> findById(Long runId) {
        return Optional.ofNullable(runs.selectById(runId)).map(this::snapshot);
    }

    @Transactional
    public void insert(AgentRunCreateData data) {
        runs.insert(toEntity(data));
        logPersisted(data.runId(), data);
    }

    /**
     * 在一个短事务中接受运行并写入首条公开事件；并发幂等冲突直接复用数据库中的胜者。
     *
     * @param data 已固定范围与版本的运行数据
     * @return 新运行或并发胜者的快照
     */
    @Transactional
    public AgentRunAcceptanceResult accept(AgentRunCreateData data) {
        AgentRunEntity entity = toEntity(data);
        Long runId = runs.insertIfAbsent(entity);
        if (runId == null) {
            AgentRunSnapshot existing = findEntityByOperatorAndIdempotencyKey(
                            data.operatorId(), data.idempotencyKey())
                    .map(this::snapshot)
                    .orElseThrow(AgentRunNotFoundException::new);
            return new AgentRunAcceptanceResult(existing, false);
        }
        logPersisted(runId, data);
        events.append(runId, AgentEventType.RUN_ACCEPTED, "accepted", data.acceptedAt());
        AgentRunEntity created = Optional.ofNullable(runs.selectById(runId))
                .orElseThrow(AgentRunNotFoundException::new);
        return new AgentRunAcceptanceResult(snapshot(created), true);
    }

    private Optional<AgentRunEntity> findEntityByOperatorAndIdempotencyKey(
            String operatorId,
            String idempotencyKey
    ) {
        return Optional.ofNullable(runs.selectOne(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getOperatorId, operatorId)
                .eq(AgentRunEntity::getIdempotencyKey, idempotencyKey)));
    }

    private AgentRunEntity toEntity(AgentRunCreateData data) {
        AgentScopeSnapshot scope = data.scope();
        AgentVersionSnapshot versions = data.versions();
        return AgentRunEntity.builder()
                .id(data.runId()).operatorId(data.operatorId()).idempotencyKey(data.idempotencyKey())
                .requestHash(data.requestHash()).taskType(data.taskType())
                .questionHash(data.questionHash()).questionLength(data.questionLength())
                .projectId(scope.projectId()).projectIdentifier(scope.projectIdentifier())
                .branchId(scope.branchId()).branchName(scope.branch())
                .snapshotId(scope.snapshotId()).commitHash(scope.commit())
                .knowledgeGenerationId(scope.knowledgeGenerationId())
                .agentName(versions.agentName()).modelName(versions.modelName())
                .configSummary(versions.configSummary())
                .status(AgentRunStatus.ACCEPTED.name())
                .eventSequence(0L)
                .stepCount(0).modelCallCount(0).retrievalCount(0).trimmedCharacterCount(0)
                .acceptedAt(data.acceptedAt()).updatedAt(data.acceptedAt())
                .build();
    }

    private void logPersisted(Long runId, AgentRunCreateData data) {
        AgentScopeSnapshot scope = data.scope();
        log.info("agent_run persisted runId={} project={} branch={} status={}",
                runId, scope.projectIdentifier(), scope.branch(), AgentRunStatus.ACCEPTED);
    }

    @Transactional
    public boolean markRunning(Long runId, Instant startedAt) {
        boolean updated = runs.markRunning(runId, startedAt) == 1;
        if (updated) {
            events.append(runId, AgentEventType.RUN_STARTED, "running", startedAt);
        }
        log.info("agent_run transition runId={} from=ACCEPTED to=RUNNING applied={}", runId, updated);
        return updated;
    }

    @Transactional
    public boolean complete(Long runId, TrustedProjectQaResult result, AgentExecutionUsage usage, Instant finishedAt) {
        int updated = runs.complete(
                runId, result.resultType().name(), result.basis() == null ? null : result.basis().name(), result.text(),
                result.refusalReason() == null ? null : result.refusalReason().name(),
                usage.stepCount(), usage.modelCallCount(), usage.retrievalCount(), usage.trimmedCharacterCount(),
                usage.inputTokens(), usage.outputTokens(), usage.elapsedMillis(), finishedAt);
        if (updated != 1) {
            // 超时或失败 CAS 已先到达终态时，迟到回答和引用都必须被丢弃。
            log.warn("agent_run completion ignored runId={} reason=terminal_compare_and_set_failed", runId);
            return false;
        }
        evidence.replaceCitations(runId, result.citations());
        events.append(runId, AgentEventType.RUN_COMPLETED, result.resultType().name(), finishedAt);
        log.info("agent_run transition runId={} from=RUNNING to=COMPLETED resultType={} citationCount={} "
                        + "stepCount={} modelCallCount={} tokenUsageKnown={}",
                runId, result.resultType(), result.citations().size(), usage.stepCount(), usage.modelCallCount(),
                usage.inputTokens() != null && usage.outputTokens() != null);
        return true;
    }

    @Transactional
    public boolean finishWithError(
            Long runId,
            AgentErrorCode code,
            boolean terminated,
            AgentExecutionUsage usage,
            Instant finishedAt
    ) {
        AgentRunStatus targetStatus = terminated ? AgentRunStatus.TERMINATED : AgentRunStatus.FAILED;
        boolean updated = runs.finishWithError(
                runId, targetStatus.name(), code.name(),
                usage.stepCount(), usage.modelCallCount(), usage.retrievalCount(), usage.trimmedCharacterCount(),
                usage.inputTokens(), usage.outputTokens(), usage.elapsedMillis(), finishedAt) == 1;
        if (updated) {
            events.append(runId, terminated ? AgentEventType.RUN_TERMINATED : AgentEventType.RUN_FAILED,
                    code.name(), finishedAt);
        }
        log.info("agent_run terminal transition runId={} targetStatus={} errorCode={} applied={} stepCount={} "
                        + "modelCallCount={}",
                runId, targetStatus, code, updated,
                usage.stepCount(), usage.modelCallCount());
        return updated;
    }

    @Transactional(readOnly = true)
    public List<AgentRunSnapshot> findNonTerminalRuns() {
        return runs.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                        .in(AgentRunEntity::getStatus, AgentRunStatus.ACCEPTED.name(), AgentRunStatus.RUNNING.name())
                        .orderByAsc(AgentRunEntity::getAcceptedAt, AgentRunEntity::getId))
                .stream().map(this::snapshot).toList();
    }

    private AgentRunSnapshot snapshot(AgentRunEntity entity) {
        List<AgentCitationSnapshot> citationViews = citationViews(entity.getId());
        return new AgentRunSnapshot(
                entity.getId(), entity.getOperatorId(), entity.getIdempotencyKey(), entity.getRequestHash(),
                entity.getTaskType(), AgentRunStatus.valueOf(entity.getStatus()),
                enumValue(AgentResultType.class, entity.getResultType()), answerBasis(entity, citationViews),
                entity.getResultText(),
                enumValue(AgentRefusalReason.class, entity.getRefusalReason()),
                enumValue(AgentErrorCode.class, entity.getErrorCode()),
                new AgentScopeSnapshot(
                        entity.getProjectId(), entity.getProjectIdentifier(), entity.getBranchId(), entity.getBranchName(),
                        entity.getSnapshotId(), entity.getCommitHash(), entity.getKnowledgeGenerationId(),
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(
                        entity.getAgentName(), entity.getModelName(), entity.getConfigSummary()),
                entity.getQuestionLength(), entity.getStepCount(), entity.getModelCallCount(),
                entity.getInputTokens(), entity.getOutputTokens(), entity.getAcceptedAt(), entity.getStartedAt(),
                entity.getFinishedAt(), citationViews);
    }

    private AnswerBasis answerBasis(AgentRunEntity entity, List<AgentCitationSnapshot> citationViews) {
        if (entity.getAnswerBasis() != null) {
            return AnswerBasis.valueOf(entity.getAnswerBasis());
        }
        if (!AgentResultType.ANSWER.name().equals(entity.getResultType())) {
            return null;
        }
        boolean knowledge = citationViews.stream()
                .anyMatch(value -> value.sourceType() == EvidenceSourceType.KNOWLEDGE);
        boolean code = citationViews.stream().anyMatch(value -> value.sourceType() == EvidenceSourceType.CODE);
        if (knowledge && code) {
            return AnswerBasis.MIXED;
        }
        if (knowledge) {
            return AnswerBasis.BUSINESS_RULE;
        }
        if (code) {
            return AnswerBasis.CURRENT_IMPLEMENTATION;
        }
        throw new IllegalStateException("completed answer has no safe citation type for basis derivation");
    }

    private List<AgentCitationSnapshot> citationViews(Long runId) {
        return evidence.findCitations(runId);
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
