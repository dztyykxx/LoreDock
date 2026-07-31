package io.github.loredock.qa.model.snapshot;

import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import java.util.List;

/** 供应用层组装 HTTP 安全响应的完整问答快照；基础设施层必须显式挑选公开字段，不得直接序列化。 */
public record WebQaQuestionSnapshot(
        WebQaQuestionRecord question,
        AgentRunSnapshot run,
        WebQaTrustState trustState,
        List<WebQaMessageRecord> messages
) {
    public WebQaQuestionSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
