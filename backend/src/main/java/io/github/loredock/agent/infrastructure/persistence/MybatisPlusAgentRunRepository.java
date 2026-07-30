package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.loredock.agent.application.AgentCitationSnapshot;
import io.github.loredock.agent.application.AgentExecutionUsage;
import io.github.loredock.agent.application.AgentRunCreateData;
import io.github.loredock.agent.application.AgentRunRepository;
import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.agent.domain.EvidenceSourceType;
import io.github.loredock.agent.domain.TrustedProjectQaResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatis-Plus 运行仓储。所有状态变化使用数据库比较更新；完成 CAS 与引用写入共享短事务，
 * 模型和工具等待永远发生在事务外。
 */
@Repository
@Slf4j
public class MybatisPlusAgentRunRepository implements AgentRunRepository {

    private final AgentRunMapper runs;
    private final AgentEvidenceMapper evidence;
    private final AgentCitationMapper citations;

    /** @param runs 运行 Mapper @param evidence 证据 Mapper @param citations 引用 Mapper */
    public MybatisPlusAgentRunRepository(
            AgentRunMapper runs,
            AgentEvidenceMapper evidence,
            AgentCitationMapper citations
    ) {
        this.runs = runs;
        this.evidence = evidence;
        this.citations = citations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRunSnapshot> findByOperatorAndIdempotencyKey(String operatorId, String idempotencyKey) {
        AgentRunEntity entity = runs.selectOne(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getOperatorId, operatorId)
                .eq(AgentRunEntity::getIdempotencyKey, idempotencyKey));
        return Optional.ofNullable(entity).map(this::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRunSnapshot> findById(UUID runId) {
        return Optional.ofNullable(runs.selectById(runId)).map(this::snapshot);
    }

    @Override
    @Transactional
    public void insert(AgentRunCreateData data) {
        AgentScopeSnapshot scope = data.scope();
        AgentVersionSnapshot versions = data.versions();
        AgentRunEntity entity = AgentRunEntity.builder()
                .id(data.runId()).operatorId(data.operatorId()).idempotencyKey(data.idempotencyKey())
                .requestHash(data.requestHash()).taskType(data.taskType())
                .questionHash(data.questionHash()).questionLength(data.questionLength())
                .projectId(scope.projectId()).projectIdentifier(scope.projectIdentifier())
                .branchId(scope.branchId()).branchName(scope.branch())
                .snapshotId(scope.snapshotId()).commitHash(scope.commit())
                .knowledgeGenerationId(scope.knowledgeGenerationId())
                .skillVersionId(versions.skillVersionId()).skillName(versions.skillName())
                .skillVersion(versions.skillVersion()).skillContentHash(versions.skillContentHash())
                .modelProvider(versions.modelProvider()).modelName(versions.modelName())
                .outputSchemaVersion(versions.outputSchemaVersion())
                .toolPolicyVersion(versions.toolPolicyVersion()).limitPolicyVersion(versions.limitPolicyVersion())
                .status(AgentRunStatus.ACCEPTED.name())
                .eventSequence(0L)
                .stepCount(0).modelCallCount(0).retrievalCount(0).trimmedCharacterCount(0)
                .acceptedAt(data.acceptedAt()).updatedAt(data.acceptedAt())
                .build();
        runs.insert(entity);
        log.info("agent_run persisted runId={} project={} branch={} status={}",
                data.runId(), scope.projectIdentifier(), scope.branch(), AgentRunStatus.ACCEPTED);
    }

    @Override
    @Transactional
    public boolean markRunning(UUID runId, Instant startedAt) {
        boolean updated = runs.markRunning(runId, startedAt) == 1;
        log.info("agent_run transition runId={} from=ACCEPTED to=RUNNING applied={}", runId, updated);
        return updated;
    }

    @Override
    @Transactional
    public boolean complete(UUID runId, TrustedProjectQaResult result, AgentExecutionUsage usage, Instant finishedAt) {
        int updated = runs.complete(
                runId, result.resultType().name(), result.text(),
                result.refusalReason() == null ? null : result.refusalReason().name(),
                usage.stepCount(), usage.modelCallCount(), usage.retrievalCount(), usage.trimmedCharacterCount(),
                usage.inputTokens(), usage.outputTokens(), usage.elapsedMillis(), finishedAt);
        if (updated != 1) {
            // 超时或失败 CAS 已先到达终态时，迟到回答和引用都必须被丢弃。
            log.warn("agent_run completion ignored runId={} reason=terminal_compare_and_set_failed", runId);
            return false;
        }
        for (int index = 0; index < result.citations().size(); index++) {
            citations.insert(AgentCitationEntity.builder()
                    .id(UUID.randomUUID()).runId(runId).evidenceId(result.citations().get(index))
                    .citationOrder(index + 1).createdAt(finishedAt).build());
        }
        log.info("agent_run transition runId={} from=RUNNING to=COMPLETED resultType={} citationCount={} "
                        + "stepCount={} modelCallCount={} tokenUsageKnown={}",
                runId, result.resultType(), result.citations().size(), usage.stepCount(), usage.modelCallCount(),
                usage.inputTokens() != null && usage.outputTokens() != null);
        return true;
    }

    @Override
    @Transactional
    public boolean finishWithError(
            UUID runId,
            AgentErrorCode code,
            boolean terminated,
            AgentExecutionUsage usage,
            Instant finishedAt
    ) {
        boolean updated = runs.finishWithError(
                runId, terminated ? AgentRunStatus.TERMINATED.name() : AgentRunStatus.FAILED.name(), code.name(),
                usage.stepCount(), usage.modelCallCount(), usage.retrievalCount(), usage.trimmedCharacterCount(),
                usage.inputTokens(), usage.outputTokens(), usage.elapsedMillis(), finishedAt) == 1;
        log.info("agent_run terminal transition runId={} targetStatus={} errorCode={} applied={} stepCount={} "
                        + "modelCallCount={}",
                runId, terminated ? AgentRunStatus.TERMINATED : AgentRunStatus.FAILED, code, updated,
                usage.stepCount(), usage.modelCallCount());
        return updated;
    }

    @Override
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
                enumValue(AgentResultType.class, entity.getResultType()), entity.getResultText(),
                enumValue(AgentRefusalReason.class, entity.getRefusalReason()),
                enumValue(AgentErrorCode.class, entity.getErrorCode()),
                new AgentScopeSnapshot(
                        entity.getProjectId(), entity.getProjectIdentifier(), entity.getBranchId(), entity.getBranchName(),
                        entity.getSnapshotId(), entity.getCommitHash(), entity.getKnowledgeGenerationId(),
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(
                        entity.getSkillVersionId(), entity.getSkillName(), entity.getSkillVersion(),
                        entity.getSkillContentHash(), entity.getModelProvider(), entity.getModelName(),
                        entity.getOutputSchemaVersion(), entity.getToolPolicyVersion(), entity.getLimitPolicyVersion()),
                entity.getQuestionLength(), entity.getStepCount(), entity.getModelCallCount(),
                entity.getInputTokens(), entity.getOutputTokens(), entity.getAcceptedAt(), entity.getStartedAt(),
                entity.getFinishedAt(), citationViews);
    }

    private List<AgentCitationSnapshot> citationViews(UUID runId) {
        List<AgentCitationEntity> rows = citations.selectList(new LambdaQueryWrapper<AgentCitationEntity>()
                .eq(AgentCitationEntity::getRunId, runId)
                .orderByAsc(AgentCitationEntity::getCitationOrder));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> evidenceIds = rows.stream().map(AgentCitationEntity::getEvidenceId).toList();
        Map<UUID, AgentEvidenceEntity> byId = new HashMap<>();
        evidence.selectBatchIds(evidenceIds).forEach(value -> byId.put(value.getId(), value));
        List<AgentCitationSnapshot> result = new ArrayList<>();
        for (AgentCitationEntity row : rows) {
            AgentEvidenceEntity source = byId.get(row.getEvidenceId());
            if (source != null) {
                result.add(new AgentCitationSnapshot(
                        source.getId(), EvidenceSourceType.valueOf(source.getSourceType()), source.getDocumentId(),
                        source.getSnapshotId(), source.getProjectIdentifier(), source.getBranchName(),
                        source.getCommitHash(), source.getRepositoryPath(), source.getTitle(),
                        source.getSourceUpdatedAt(), row.getCitationOrder()));
            }
        }
        result.sort(Comparator.comparingInt(AgentCitationSnapshot::order));
        return List.copyOf(result);
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
