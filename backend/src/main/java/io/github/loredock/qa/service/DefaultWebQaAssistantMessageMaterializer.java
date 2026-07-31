package io.github.loredock.qa.service;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以 `agent_run` 为终态事实来源，通过唯一消息角色实现可重试、自愈的助手消息投影。 */
@Service
@Slf4j
public class DefaultWebQaAssistantMessageMaterializer {
    private final WebQaMessageDataService messages;

    /** @param messages 问答消息仓储 */
    public DefaultWebQaAssistantMessageMaterializer(WebQaMessageDataService messages) {
        this.messages = messages;
    }

    @Transactional
    public boolean materialize(WebQaQuestionRecord question, AgentRun run) {
        if (!question.runId().equals(run.runId())) {
            throw new IllegalArgumentException("web QA question and run mismatch");
        }
        if (run.status() != AgentRun.Status.COMPLETED) {
            return false;
        }
        if (run.resultType() == null || run.resultText() == null || run.resultText().isBlank()
                || run.finishedAt() == null) {
            throw new IllegalStateException("completed agent run has no public result");
        }
        if (run.resultType() == AgentRun.ResultType.ANSWER && run.refusalReason() != null) {
            throw new IllegalStateException("answer run contains refusal reason");
        }
        if (run.resultType() == AgentRun.ResultType.REFUSAL && run.refusalReason() == null) {
            throw new IllegalStateException("refusal run has no reason");
        }
        boolean inserted = messages.insertIfAbsent(new WebQaMessageRecord(
                null, question.id(), WebQaMessageRole.ASSISTANT, run.resultText(),
                run.resultType(), run.refusalReason(), run.finishedAt())).isPresent();
        log.info("web_qa assistant projection traceId={} questionId={} runId={} project={} branch={} "
                        + "resultType={} inserted={}",
                traceId(question.id()), question.id(), run.runId(), question.projectIdentifier(), question.branch(),
                run.resultType(), inserted);
        return inserted;
    }

    private String traceId(Long fallbackId) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? fallbackId.toString() : current;
    }
}
