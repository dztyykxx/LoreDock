package io.github.loredock.testsupport;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;

/** 测试中把 QA 内部持久化聚合显式转换为公开契约。 */
public final class QaApiFixtures {
    private QaApiFixtures() {
    }

    /** @return 与内部聚合语义一致的公开问答事实 */
    public static QaQuestion question(WebQaQuestionSnapshot snapshot) {
        AgentRun run = snapshot.run();
        AgentRun.Scope scope = run.scope();
        return new QaQuestion(
                snapshot.question().id(), run.runId(),
                new QaQuestion.Scope(scope.projectId(), scope.projectIdentifier(), scope.branchId(), scope.branch(),
                        scope.commit(), scope.hasCodeSnapshot()), snapshot.question().createdAt(),
                QaQuestion.Status.valueOf(run.status().name()),
                enumValue(QaQuestion.ResultType.class, run.resultType()),
                QaQuestion.TrustState.valueOf(snapshot.trustState().name()),
                enumValue(QaQuestion.AnswerBasis.class, run.answerBasis()),
                enumValue(QaQuestion.RefusalReason.class, run.refusalReason()),
                enumValue(QaQuestion.ErrorCode.class, run.errorCode()), run.resultText(),
                run.stepCount(), run.modelCallCount(), run.finishedAt(),
                snapshot.messages().stream().map(message -> new QaQuestion.Message(
                        message.id(), QaQuestion.MessageRole.valueOf(message.role().name()), message.content(),
                        enumValue(QaQuestion.ResultType.class, message.resultType()),
                        enumValue(QaQuestion.RefusalReason.class, message.refusalReason()),
                        message.createdAt())).toList(),
                run.citations().stream().map(citation -> {
                    AgentRun.SourceMetadata metadata = citation.sourceMetadata();
                    return new QaQuestion.Citation(
                            citation.evidenceId(), citation.documentId(), citation.snapshotId(), citation.order(),
                            QaQuestion.EvidenceSourceType.valueOf(citation.sourceType().name()),
                            citation.projectIdentifier(), citation.branch(), citation.commit(),
                            citation.repositoryPath(), citation.title(), citation.sourceUpdatedAt(),
                            metadata == null ? null : metadata.scopeType(),
                            metadata == null ? null : metadata.knowledgeSourceType(),
                            metadata == null ? null : metadata.wikiUrl(),
                            metadata == null ? null : metadata.originalFilename());
                }).toList());
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, Enum<?> value) {
        return value == null ? null : Enum.valueOf(type, value.name());
    }
}
