package io.github.loredock.qa.application;

import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.qa.domain.WebQaMessageRole;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 以 `agent_run` 为终态事实来源，通过唯一消息角色实现可重试、自愈的助手消息投影。 */
@Service
@Slf4j
public class DefaultWebQaAssistantMessageMaterializer implements WebQaAssistantMessageMaterializer {
    private final WebQaMessageRepository messages;

    /** @param messages 问答消息仓储 */
    public DefaultWebQaAssistantMessageMaterializer(WebQaMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    @Transactional
    public boolean materialize(WebQaQuestionRecord question, AgentRunSnapshot run) {
        if (!question.runId().equals(run.runId())) {
            throw new IllegalArgumentException("web QA question and run mismatch");
        }
        if (run.status() != AgentRunStatus.COMPLETED) {
            return false;
        }
        if (run.resultType() == null || run.resultText() == null || run.resultText().isBlank()
                || run.finishedAt() == null) {
            throw new IllegalStateException("completed agent run has no public result");
        }
        if (run.resultType() == AgentResultType.ANSWER && run.refusalReason() != null) {
            throw new IllegalStateException("answer run contains refusal reason");
        }
        if (run.resultType() == AgentResultType.REFUSAL && run.refusalReason() == null) {
            throw new IllegalStateException("refusal run has no reason");
        }
        boolean inserted = messages.insertIfAbsent(new WebQaMessageRecord(
                UUID.randomUUID(), question.id(), WebQaMessageRole.ASSISTANT, run.resultText(),
                run.resultType(), run.refusalReason(), run.finishedAt()));
        log.info("web_qa assistant projection traceId={} questionId={} runId={} project={} branch={} "
                        + "resultType={} inserted={}",
                traceId(question.id()), question.id(), run.runId(), question.projectIdentifier(), question.branch(),
                run.resultType(), inserted);
        return inserted;
    }

    private String traceId(UUID fallbackId) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? fallbackId.toString() : current;
    }
}
