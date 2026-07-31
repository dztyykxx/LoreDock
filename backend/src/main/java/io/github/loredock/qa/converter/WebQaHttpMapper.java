package io.github.loredock.qa.converter;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.qa.model.response.WebQaCitationResponse;
import io.github.loredock.qa.model.response.WebQaMessageResponse;
import io.github.loredock.qa.model.response.WebQaQuestionResponse;
import io.github.loredock.qa.model.response.WebQaScopeResponse;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;

/** 把应用完整快照显式挑选为公开字段，防止新增内部字段被框架自动序列化。 */
public final class WebQaHttpMapper {
    private WebQaHttpMapper() {
    }

    /** @return 不含操作者、摘要、内部版本和对象位置的 HTTP 快照 */
    public static WebQaQuestionResponse toResponse(WebQaQuestionSnapshot snapshot, long lastEventSequence) {
        var run = snapshot.run();
        var scope = run.scope();
        return new WebQaQuestionResponse(
                snapshot.question().id(), run.runId(),
                new WebQaScopeResponse(
                        scope.projectIdentifier(), scope.branch(), scope.commit(), scope.hasCodeSnapshot()),
                snapshot.question().createdAt(), run.status(), run.resultType(), snapshot.trustState(),
                run.answerBasis(), run.refusalReason(), run.errorCode(),
                WebQaFailureMessageMapper.from(run.status(), run.errorCode()), run.resultText(),
                run.stepCount(), run.modelCallCount(), lastEventSequence,
                snapshot.messages().stream().map(WebQaHttpMapper::toMessage).toList(),
                run.citations().stream().map(WebQaHttpMapper::toCitation).toList());
    }

    private static WebQaMessageResponse toMessage(WebQaMessageRecord message) {
        return new WebQaMessageResponse(
                message.id(), message.role(), message.content(), message.resultType(),
                message.refusalReason(), message.createdAt());
    }

    private static WebQaCitationResponse toCitation(AgentRun.Citation citation) {
        AgentRun.SourceMetadata metadata = citation.sourceMetadata();
        return new WebQaCitationResponse(
                citation.order(), citation.sourceType(), citation.projectIdentifier(), citation.branch(),
                citation.commit(), citation.repositoryPath(), citation.title(), citation.sourceUpdatedAt(),
                metadata == null ? null : metadata.scopeType(),
                metadata == null ? null : metadata.knowledgeSourceType(),
                metadata == null ? null : metadata.wikiUrl(),
                metadata == null ? null : metadata.originalFilename());
    }
}
