package io.github.loredock.testsupport;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.model.snapshot.AgentEventSnapshot;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;

/** 测试中把 Agent 内部持久化快照显式转换为跨模块契约，避免复制大段无关夹具字段。 */
public final class AgentApiFixtures {
    private AgentApiFixtures() {
    }

    /** @return 与给定内部快照语义一致的公开运行契约 */
    public static AgentRun run(AgentRunSnapshot snapshot) {
        var scope = snapshot.scope();
        return new AgentRun(
                snapshot.runId(), AgentRun.Status.valueOf(snapshot.status().name()),
                enumValue(AgentRun.ResultType.class, snapshot.resultType()),
                enumValue(AgentRun.AnswerBasis.class, snapshot.answerBasis()), snapshot.resultText(),
                enumValue(AgentRun.RefusalReason.class, snapshot.refusalReason()),
                enumValue(AgentRun.ErrorCode.class, snapshot.errorCode()),
                new AgentRun.Scope(scope.projectId(), scope.projectIdentifier(), scope.branchId(), scope.branch(),
                        scope.snapshotId(), scope.commit(), scope.knowledgeGenerationId()),
                snapshot.stepCount(), snapshot.modelCallCount(), snapshot.acceptedAt(), snapshot.startedAt(),
                snapshot.finishedAt(), snapshot.citations().stream().map(citation -> new AgentRun.Citation(
                        citation.evidenceId(), AgentRun.EvidenceSourceType.valueOf(citation.sourceType().name()),
                        citation.documentId(), citation.snapshotId(), citation.projectIdentifier(), citation.branch(),
                        citation.commit(), citation.repositoryPath(), citation.title(), citation.sourceUpdatedAt(),
                        citation.order(), new AgentRun.SourceMetadata(
                                citation.sourceMetadata().schemaVersion(), citation.sourceMetadata().scopeType(),
                                citation.sourceMetadata().knowledgeSourceType(), citation.sourceMetadata().wikiUrl(),
                                citation.sourceMetadata().originalFilename()))).toList());
    }

    /** @return 与给定内部快照语义一致的公开事件契约 */
    public static AgentEvent event(AgentEventSnapshot snapshot) {
        return new AgentEvent(snapshot.eventId(), snapshot.runId(), snapshot.sequence(),
                AgentEvent.Type.valueOf(snapshot.type().name()), snapshot.payload(), snapshot.createdAt());
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, Enum<?> value) {
        return value == null ? null : Enum.valueOf(type, value.name());
    }
}
