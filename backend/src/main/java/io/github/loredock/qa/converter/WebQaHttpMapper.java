package io.github.loredock.qa.converter;

import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.model.response.WebQaCitationResponse;
import io.github.loredock.qa.model.response.WebQaMessageResponse;
import io.github.loredock.qa.model.response.WebQaQuestionResponse;
import io.github.loredock.qa.model.response.WebQaScopeResponse;

/** 把应用完整快照显式挑选为公开字段，防止新增内部字段被框架自动序列化。 */
public final class WebQaHttpMapper {
    private WebQaHttpMapper() {
    }

    /** @return 不含操作者、摘要、内部版本和对象位置的 HTTP 快照 */
    public static WebQaQuestionResponse toResponse(QaQuestion snapshot, long lastEventSequence) {
        var scope = snapshot.scope();
        return new WebQaQuestionResponse(
                snapshot.questionId(), snapshot.runId(),
                new WebQaScopeResponse(
                        scope.projectIdentifier(), scope.branch(), scope.commit(), scope.codeSnapshotAvailable()),
                snapshot.createdAt(), snapshot.status(), snapshot.resultType(), snapshot.trustState(),
                snapshot.answerBasis(), snapshot.refusalReason(), snapshot.errorCode(),
                WebQaFailureMessageMapper.from(snapshot.status(), snapshot.errorCode()), snapshot.resultText(),
                snapshot.stepCount(), snapshot.modelCallCount(), lastEventSequence,
                snapshot.messages().stream().map(WebQaHttpMapper::toMessage).toList(),
                snapshot.citations().stream().map(WebQaHttpMapper::toCitation).toList());
    }

    private static WebQaMessageResponse toMessage(QaQuestion.Message message) {
        return new WebQaMessageResponse(
                message.id(), message.role(), message.content(), message.resultType(),
                message.refusalReason(), message.createdAt());
    }

    private static WebQaCitationResponse toCitation(QaQuestion.Citation citation) {
        return new WebQaCitationResponse(
                citation.order(), citation.sourceType(), citation.projectIdentifier(), citation.branch(),
                citation.commit(), citation.repositoryPath(), citation.title(), citation.sourceUpdatedAt(),
                citation.scopeType(), citation.knowledgeSourceType(), citation.wikiUrl(), citation.originalFilename());
    }
}
