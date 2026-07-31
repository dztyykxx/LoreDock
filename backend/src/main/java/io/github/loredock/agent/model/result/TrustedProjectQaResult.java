package io.github.loredock.agent.model.result;

import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AnswerBasis;
import java.util.List;

/** 服务端完成引用与发布边界校验后可以写入终态的结果。 */
public record TrustedProjectQaResult(
        AgentResultType resultType,
        AnswerBasis basis,
        String text,
        AgentRefusalReason refusalReason,
        List<Long> citations
) {
    public TrustedProjectQaResult {
        citations = List.copyOf(citations);
    }
}
