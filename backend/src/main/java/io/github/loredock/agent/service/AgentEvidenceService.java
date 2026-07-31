package io.github.loredock.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.mapper.AgentEvidenceMapper;
import io.github.loredock.agent.model.entity.AgentEvidenceEntity;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.snapshot.AgentCitationSnapshot;
import io.github.loredock.agent.model.snapshot.EvidenceSourceMetadata;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Agent 证据服务，直接使用 Mapper 持久化来源元数据和最终引用顺序。 */
@Service
@Slf4j
public class AgentEvidenceService {

    private final AgentEvidenceMapper evidence;
    private final Clock timeProvider;
    private final ObjectMapper objectMapper;

    /**
     * @param evidence 证据 Mapper
     * @param timeProvider UTC 时间源
     * @param objectMapper 版本化安全来源 JSON 编解码器
     */
    public AgentEvidenceService(
            AgentEvidenceMapper evidence,
            Clock timeProvider,
            ObjectMapper objectMapper
    ) {
        this.evidence = evidence;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 持久化本次工具调用的来源元数据，并返回带数据库生成 ID 的证据。
     *
     * @param runId 当前运行标识
     * @param values 工具返回的来源证据（ID 尚未分配）
     * @return 与输入同序、已携带真实证据 ID 的持久化证据
     */
    @Transactional
    public List<AgentEvidence> saveAll(Long runId, List<AgentEvidence> values) {
        int sequence = evidence.selectCount(new LambdaQueryWrapper<AgentEvidenceEntity>()
                .eq(AgentEvidenceEntity::getRunId, runId)).intValue();
        List<AgentEvidence> saved = new ArrayList<>(values.size());
        for (AgentEvidence value : values) {
            if (!runId.equals(value.runId())) {
                throw new IllegalArgumentException("evidence run mismatch");
            }
            sequence++;
            AgentEvidenceEntity entity = AgentEvidenceEntity.builder()
                    .id(value.id()).runId(runId).evidenceKey("E" + sequence)
                    .sourceType(value.sourceType().name()).retained(value.retained())
                    .cited(false).citationOrder(null).relevance(value.relevance())
                    .documentId(value.documentId()).snapshotId(value.snapshotId())
                    .projectIdentifier(value.projectIdentifier()).branchName(value.branch())
                    .commitHash(value.commit()).repositoryPath(value.repositoryPath()).title(value.title())
                    .sourceUpdatedAt(value.sourceUpdatedAt()).metadata(metadataJson(value.sourceMetadata()))
                    .createdAt(timeProvider.instant()).build();
            evidence.insert(entity);
            saved.add(domain(entity));
        }
        log.info("agent_evidence persisted runId={} evidenceCount={}", runId, values.size());
        return List.copyOf(saved);
    }

    @Transactional(readOnly = true)
    public List<AgentEvidence> findByRunId(Long runId) {
        return evidence.selectList(new LambdaQueryWrapper<AgentEvidenceEntity>()
                        .eq(AgentEvidenceEntity::getRunId, runId)
                        .orderByAsc(AgentEvidenceEntity::getEvidenceKey))
                .stream().map(this::domain).toList();
    }

    @Transactional
    public void replaceCitations(Long runId, List<Long> evidenceIds) {
        evidence.update(null, new LambdaUpdateWrapper<AgentEvidenceEntity>()
                .eq(AgentEvidenceEntity::getRunId, runId)
                .set(AgentEvidenceEntity::getCited, false)
                .set(AgentEvidenceEntity::getCitationOrder, null));
        for (int index = 0; index < evidenceIds.size(); index++) {
            int updated = evidence.update(null, new LambdaUpdateWrapper<AgentEvidenceEntity>()
                    .eq(AgentEvidenceEntity::getRunId, runId)
                    .eq(AgentEvidenceEntity::getId, evidenceIds.get(index))
                    .eq(AgentEvidenceEntity::getRetained, true)
                    .set(AgentEvidenceEntity::getCited, true)
                    .set(AgentEvidenceEntity::getCitationOrder, index + 1));
            if (updated != 1) {
                throw new IllegalStateException("agent citation evidence missing or not retained");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<AgentCitationSnapshot> findCitations(Long runId) {
        return evidence.selectList(new LambdaQueryWrapper<AgentEvidenceEntity>()
                        .eq(AgentEvidenceEntity::getRunId, runId)
                        .eq(AgentEvidenceEntity::getCited, true)
                        .orderByAsc(AgentEvidenceEntity::getCitationOrder))
                .stream().map(this::citation).toList();
    }

    private AgentEvidence domain(AgentEvidenceEntity value) {
        return new AgentEvidence(
                value.getId(), value.getRunId(), EvidenceSourceType.valueOf(value.getSourceType()),
                Boolean.TRUE.equals(value.getRetained()), value.getRelevance(), value.getDocumentId(),
                value.getSnapshotId(), value.getProjectIdentifier(), value.getBranchName(), value.getCommitHash(),
                value.getRepositoryPath(), value.getTitle(), value.getSourceUpdatedAt(), metadata(value.getMetadata()));
    }

    private AgentCitationSnapshot citation(AgentEvidenceEntity source) {
        return new AgentCitationSnapshot(
                source.getId(), EvidenceSourceType.valueOf(source.getSourceType()), source.getDocumentId(),
                source.getSnapshotId(), source.getProjectIdentifier(), source.getBranchName(), source.getCommitHash(),
                source.getRepositoryPath(), source.getTitle(), source.getSourceUpdatedAt(), source.getCitationOrder(),
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
