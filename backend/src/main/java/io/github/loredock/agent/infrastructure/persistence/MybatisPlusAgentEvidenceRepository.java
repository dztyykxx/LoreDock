package io.github.loredock.agent.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.loredock.agent.application.AgentCitationSnapshot;
import io.github.loredock.agent.application.AgentEvidenceRepository;
import io.github.loredock.agent.domain.AgentEvidence;
import io.github.loredock.agent.domain.EvidenceSourceType;
import io.github.loredock.agent.domain.EvidenceSourceMetadata;
import io.github.loredock.platform.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MyBatis-Plus 证据仓储，只持久化来源元数据和最终引用顺序。 */
@Repository
@Slf4j
public class MybatisPlusAgentEvidenceRepository implements AgentEvidenceRepository {

    private final AgentEvidenceMapper evidence;
    private final AgentCitationMapper citations;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    /**
     * @param evidence 证据 Mapper
     * @param citations 引用 Mapper
     * @param timeProvider UTC 时间源
     * @param objectMapper 版本化安全来源 JSON 编解码器
     */
    public MybatisPlusAgentEvidenceRepository(
            AgentEvidenceMapper evidence,
            AgentCitationMapper citations,
            TimeProvider timeProvider,
            ObjectMapper objectMapper
    ) {
        this.evidence = evidence;
        this.citations = citations;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void saveAll(UUID runId, List<AgentEvidence> values) {
        int sequence = evidence.selectCount(new LambdaQueryWrapper<AgentEvidenceEntity>()
                .eq(AgentEvidenceEntity::getRunId, runId)).intValue();
        for (AgentEvidence value : values) {
            if (!runId.equals(value.runId())) {
                throw new IllegalArgumentException("evidence run mismatch");
            }
            sequence++;
            evidence.insert(AgentEvidenceEntity.builder()
                    .id(value.id()).runId(runId).evidenceKey("E" + sequence)
                    .sourceType(value.sourceType().name()).retained(value.retained()).relevance(value.relevance())
                    .documentId(value.documentId()).snapshotId(value.snapshotId())
                    .projectIdentifier(value.projectIdentifier()).branchName(value.branch())
                    .commitHash(value.commit()).repositoryPath(value.repositoryPath()).title(value.title())
                    .sourceUpdatedAt(value.sourceUpdatedAt()).metadata(metadataJson(value.sourceMetadata()))
                    .createdAt(timeProvider.now()).build());
        }
        log.info("agent_evidence persisted runId={} evidenceCount={}", runId, values.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentEvidence> findByRunId(UUID runId) {
        return evidence.selectList(new LambdaQueryWrapper<AgentEvidenceEntity>()
                        .eq(AgentEvidenceEntity::getRunId, runId)
                        .orderByAsc(AgentEvidenceEntity::getEvidenceKey))
                .stream().map(this::domain).toList();
    }

    @Override
    @Transactional
    public void replaceCitations(UUID runId, List<UUID> evidenceIds) {
        citations.delete(new LambdaUpdateWrapper<AgentCitationEntity>().eq(AgentCitationEntity::getRunId, runId));
        for (int index = 0; index < evidenceIds.size(); index++) {
            citations.insert(AgentCitationEntity.builder()
                    .id(UUID.randomUUID()).runId(runId).evidenceId(evidenceIds.get(index))
                    .citationOrder(index + 1).createdAt(timeProvider.now()).build());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentCitationSnapshot> findCitations(UUID runId) {
        List<AgentCitationEntity> rows = citations.selectList(new LambdaQueryWrapper<AgentCitationEntity>()
                .eq(AgentCitationEntity::getRunId, runId).orderByAsc(AgentCitationEntity::getCitationOrder));
        Map<UUID, AgentEvidenceEntity> byId = new HashMap<>();
        if (!rows.isEmpty()) {
            evidence.selectBatchIds(rows.stream().map(AgentCitationEntity::getEvidenceId).toList())
                    .forEach(value -> byId.put(value.getId(), value));
        }
        return rows.stream().map(row -> citation(row, byId.get(row.getEvidenceId()))).toList();
    }

    private AgentEvidence domain(AgentEvidenceEntity value) {
        return new AgentEvidence(
                value.getId(), value.getRunId(), EvidenceSourceType.valueOf(value.getSourceType()),
                Boolean.TRUE.equals(value.getRetained()), value.getRelevance(), value.getDocumentId(),
                value.getSnapshotId(), value.getProjectIdentifier(), value.getBranchName(), value.getCommitHash(),
                value.getRepositoryPath(), value.getTitle(), value.getSourceUpdatedAt(), metadata(value.getMetadata()));
    }

    private AgentCitationSnapshot citation(AgentCitationEntity row, AgentEvidenceEntity source) {
        if (source == null) {
            throw new IllegalStateException("agent citation evidence missing");
        }
        return new AgentCitationSnapshot(
                source.getId(), EvidenceSourceType.valueOf(source.getSourceType()), source.getDocumentId(),
                source.getSnapshotId(), source.getProjectIdentifier(), source.getBranchName(), source.getCommitHash(),
                source.getRepositoryPath(), source.getTitle(), source.getSourceUpdatedAt(), row.getCitationOrder(),
                metadata(source.getMetadata()));
    }

    private String metadataJson(EvidenceSourceMetadata metadata) {
        if (metadata == null || metadata.schemaVersion() == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent evidence source metadata serialization failed", exception);
        }
    }

    private EvidenceSourceMetadata metadata(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return EvidenceSourceMetadata.historicalUnknown();
        }
        try {
            return objectMapper.readValue(json, EvidenceSourceMetadata.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 历史附属字段损坏时不回查当前文档伪造来源；只保留主表中仍受外键保护的安全事实。
            log.warn("agent_evidence metadata degraded reason=invalid_or_unsupported_schema");
            return EvidenceSourceMetadata.historicalUnknown();
        }
    }
}
