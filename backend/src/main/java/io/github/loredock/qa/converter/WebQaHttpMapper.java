package io.github.loredock.qa.converter;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.model.response.WebQaCitationResponse;
import io.github.loredock.qa.model.response.WebQaMessageResponse;
import io.github.loredock.qa.model.response.WebQaQuestionResponse;
import io.github.loredock.qa.model.response.WebQaScopeResponse;
import io.github.loredock.qa.model.response.WebQaProcessEventResponse;
import io.github.loredock.qa.model.response.WebQaConversationSummaryResponse;

/** 把应用完整快照显式挑选为公开字段，防止新增内部字段被框架自动序列化。 */
public final class WebQaHttpMapper {
    private WebQaHttpMapper() {
    }

    /** @return 不含操作者、摘要、内部版本和对象位置的 HTTP 快照 */
    public static WebQaQuestionResponse toResponse(QaQuestion snapshot, long lastEventSequence) {
        return toResponse(snapshot, lastEventSequence, java.util.List.of());
    }

    /** @return 带有界持久化公开过程的 HTTP 快照 */
    public static WebQaQuestionResponse toResponse(
            QaQuestion snapshot,
            long lastEventSequence,
            java.util.List<AgentEvent> events
    ) {
        var scope = snapshot.scope();
        return new WebQaQuestionResponse(
                snapshot.questionId(), snapshot.conversationId(), snapshot.runId(),
                new WebQaScopeResponse(
                        scope.projectIdentifier(), scope.branch(), scope.commit(), scope.codeSnapshotAvailable()),
                snapshot.createdAt(), snapshot.status(), snapshot.resultType(), snapshot.trustState(),
                snapshot.answerBasis(), snapshot.refusalReason(), snapshot.errorCode(),
                WebQaFailureMessageMapper.from(snapshot.status(), snapshot.errorCode()), snapshot.resultText(),
                snapshot.stepCount(), snapshot.modelCallCount(), lastEventSequence,
                events.stream().map(WebQaHttpMapper::toProcessEvent).toList(),
                snapshot.messages().stream().map(WebQaHttpMapper::toMessage).toList(),
                snapshot.citations().stream().map(WebQaHttpMapper::toCitation).toList());
    }

    /** @return 最近会话公开摘要 */
    public static WebQaConversationSummaryResponse toResponse(QaService.ConversationSummary value) {
        return new WebQaConversationSummaryResponse(value.conversationId(), value.projectIdentifier(), value.title(),
                value.lastQuestion(), value.status(), value.createdAt(), value.updatedAt(), value.lastQuestionAt());
    }

    private static WebQaProcessEventResponse toProcessEvent(AgentEvent event) {
        WebQaSseEventMapper.requireSafe(event);
        return new WebQaProcessEventResponse(
                event.sequence(), event.type(), event.subjectType(), event.payload(), event.createdAt());
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
