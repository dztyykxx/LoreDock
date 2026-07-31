package io.github.loredock.agent.model.result;

import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AnswerBasis;
import java.util.List;

/** 模型生成但尚未被信任的 project_qa 结构化结果。 */
public record ProjectQaModelResult(
        AgentResultType resultType,
        AnswerBasis basis,
        String text,
        AgentRefusalReason refusalReason,
        List<Long> citationEvidenceIds
) {
    public ProjectQaModelResult {
        citationEvidenceIds = citationEvidenceIds == null ? List.of() : List.copyOf(citationEvidenceIds);
    }
}
