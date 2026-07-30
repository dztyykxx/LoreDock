package io.github.loredock.agent.domain;

import java.util.List;
import java.util.UUID;

/** 模型生成但尚未被信任的 project_qa 结构化结果。 */
public record ProjectQaModelResult(
        AgentResultType resultType,
        AnswerBasis basis,
        String text,
        AgentRefusalReason refusalReason,
        List<UUID> citationEvidenceIds
) {
    public ProjectQaModelResult {
        citationEvidenceIds = citationEvidenceIds == null ? List.of() : List.copyOf(citationEvidenceIds);
    }
}
