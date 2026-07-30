package io.github.loredock.agent.domain;

import java.util.List;
import java.util.UUID;

/** 服务端完成引用与发布边界校验后可以写入终态的结果。 */
public record TrustedProjectQaResult(
        AgentResultType resultType,
        AnswerBasis basis,
        String text,
        AgentRefusalReason refusalReason,
        List<UUID> citations
) {
    public TrustedProjectQaResult {
        citations = List.copyOf(citations);
    }
}
