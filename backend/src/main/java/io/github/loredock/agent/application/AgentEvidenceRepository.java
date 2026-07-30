package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEvidence;

import java.util.List;
import java.util.UUID;

/** 当前运行证据台账与最终引用的持久化端口。 */
public interface AgentEvidenceRepository {

    /** 批量保存只含来源元数据的证据，不写入检索正文。 */
    void saveAll(UUID runId, List<AgentEvidence> evidence);

    /** @return 当前运行全部有限证据元数据 */
    List<AgentEvidence> findByRunId(UUID runId);

    /** 原子替换当前运行的最终引用顺序。 */
    void replaceCitations(UUID runId, List<UUID> evidenceIds);

    /** @return 当前运行最终引用的安全视图 */
    List<AgentCitationSnapshot> findCitations(UUID runId);
}
