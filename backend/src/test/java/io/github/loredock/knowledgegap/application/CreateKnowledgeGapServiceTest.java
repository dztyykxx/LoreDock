package io.github.loredock.knowledgegap.application;

import io.github.loredock.agent.application.AgentCitationSnapshot;
import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;
import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.project.application.BranchView;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import io.github.loredock.qa.application.QueryWebQaQuestionUseCase;
import io.github.loredock.qa.application.WebQaMessageRecord;
import io.github.loredock.qa.application.WebQaQuestionRecord;
import io.github.loredock.qa.application.WebQaQuestionNotFoundException;
import io.github.loredock.qa.application.WebQaQuestionSnapshot;
import io.github.loredock.qa.domain.WebQaMessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateKnowledgeGapServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("77000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_ID = UUID.fromString("77000000-0000-0000-0000-000000000002");
    private static final UUID QUESTION_ID = UUID.fromString("77000000-0000-0000-0000-000000000003");
    private static final UUID RUN_ID = UUID.fromString("77000000-0000-0000-0000-000000000004");
    private static final UUID EVIDENCE_ID = UUID.fromString("77000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-07-30T11:00:00Z");

    private ProjectQueryUseCase projects;
    private QueryWebQaQuestionUseCase questions;
    private MemoryRepository repository;
    private CreateKnowledgeGapService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectQueryUseCase.class);
        questions = mock(QueryWebQaQuestionUseCase.class);
        repository = new MemoryRepository();
        TimeProvider time = () -> NOW;
        service = new CreateKnowledgeGapService(projects, questions, repository, time);
        when(projects.getEnabledProject("atlas", "main")).thenReturn(project("main"));
        when(projects.getEnabledProject("atlas", null)).thenReturn(project("main"));
    }

    /**
     * 业务目的：关联问答反馈必须复制服务端问题、运行终态和引用，忽略客户端伪造问题且不复制答案正文。
     */
    @Test
    void linkedQuestionCopiesOnlyServerFactsAndCitations() {
        AgentRunSnapshot run = mock(AgentRunSnapshot.class);
        AgentCitationSnapshot citation = mock(AgentCitationSnapshot.class);
        when(citation.evidenceId()).thenReturn(EVIDENCE_ID);
        when(run.runId()).thenReturn(RUN_ID);
        when(run.resultType()).thenReturn(AgentResultType.ANSWER);
        when(run.errorCode()).thenReturn(null);
        when(run.citations()).thenReturn(List.of(citation));
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                QUESTION_ID, "member", "qa-key", "a".repeat(64), PROJECT_ID, "atlas", BRANCH_ID,
                "main", RUN_ID, NOW.minusSeconds(10));
        WebQaMessageRecord user = new WebQaMessageRecord(
                UUID.randomUUID(), QUESTION_ID, WebQaMessageRole.USER, "服务端真实问题", null, null, NOW);
        WebQaMessageRecord assistant = new WebQaMessageRecord(
                UUID.randomUUID(), QUESTION_ID, WebQaMessageRole.ASSISTANT, "不得复制的完整答案",
                AgentResultType.ANSWER, null, NOW);
        when(questions.detail(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WebQaQuestionSnapshot(question, run, null, List.of(user, assistant)));

        KnowledgeGapFeedbackSnapshot result = service.create(command(
                "linked-key", KnowledgeGapType.WRONG_ANSWER, QUESTION_ID, "客户端伪造问题", "补充"));

        assertThat(result.feedback().question()).isEqualTo("服务端真实问题");
        assertThat(result.feedback().runId()).isEqualTo(RUN_ID);
        assertThat(result.feedback().resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(result.citationEvidenceIds()).containsExactly(EVIDENCE_ID);
        assertThat(result.toString()).doesNotContain("不得复制的完整答案");
        System.out.printf("测试证据：场景=关联问答反馈，questionId=%s，runId=%s，引用数=%d，伪造问题采用=false%n",
                QUESTION_ID, RUN_ID, result.citationEvidenceIds().size());
    }

    /**
     * 业务目的：即使问答 ID 对当前操作者可见，固定分支与反馈请求不一致也必须统一隐藏为问答不存在。
     */
    @Test
    void linkedQuestionFromDifferentBranchIsHidden() {
        AgentRunSnapshot run = mock(AgentRunSnapshot.class);
        when(run.runId()).thenReturn(RUN_ID);
        when(run.citations()).thenReturn(List.of());
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                QUESTION_ID, "member", "qa-key", "a".repeat(64), PROJECT_ID, "atlas", UUID.randomUUID(),
                "release", RUN_ID, NOW.minusSeconds(10));
        WebQaMessageRecord user = new WebQaMessageRecord(
                UUID.randomUUID(), QUESTION_ID, WebQaMessageRole.USER, "服务端真实问题", null, null, NOW);
        when(questions.detail(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WebQaQuestionSnapshot(question, run, null, List.of(user)));

        assertThatThrownBy(() -> service.create(command(
                "wrong-branch", KnowledgeGapType.NO_ANSWER, QUESTION_ID, null, null)))
                .isInstanceOf(WebQaQuestionNotFoundException.class);
        assertThat(repository.records).isEmpty();
        System.out.printf("测试证据：场景=关联问答分支不匹配，questionId=%s，请求分支=main，实际分支=release，结果=404%n",
                QUESTION_ID);
    }

    /**
     * 业务目的：手动反馈固定项目 main 范围且不读取问答或启动任何运行，只新增反馈事实。
     */
    @Test
    void manualFeedbackUsesMainWithoutQuestionSideEffects() {
        KnowledgeGapFeedbackSnapshot result = service.create(CreateKnowledgeGapCommand.of(
                "member", "manual-key", "atlas", null, KnowledgeGapType.OUTDATED_KNOWLEDGE,
                null, "哪条规则已经过期？", "版本变化"));

        assertThat(result.feedback().branch()).isEqualTo("main");
        assertThat(result.feedback().questionId()).isNull();
        assertThat(result.feedback().runId()).isNull();
        assertThat(result.citationEvidenceIds()).isEmpty();
        verifyNoInteractions(questions);
        System.out.printf("测试证据：场景=手动知识缺口，feedbackId=%s，项目=atlas，分支=%s，运行写入=0%n",
                result.feedback().id(), result.feedback().branch());
    }

    /**
     * 业务目的：相同键相同输入安全复用原记录，不同类型必须冲突且不能覆盖原状态。
     */
    @Test
    void retriesAreIdempotentAndChangedInputConflicts() {
        KnowledgeGapFeedbackSnapshot first = service.create(command(
                "retry-key", KnowledgeGapType.NO_ANSWER, null, "为什么？", null));
        KnowledgeGapFeedbackSnapshot retried = service.create(command(
                "retry-key", KnowledgeGapType.NO_ANSWER, null, "为什么？", null));

        assertThat(retried.feedback().id()).isEqualTo(first.feedback().id());
        assertThat(repository.records).hasSize(1);
        assertThatThrownBy(() -> service.create(command(
                "retry-key", KnowledgeGapType.WRONG_ANSWER, null, "为什么？", null)))
                .isInstanceOf(KnowledgeGapIdempotencyConflictException.class);
        assertThat(repository.records.values().iterator().next().type()).isEqualTo(KnowledgeGapType.NO_ANSWER);
        System.out.printf("测试证据：场景=知识缺口幂等，feedbackId=%s，重试记录数=%d，异参=409%n",
                first.feedback().id(), repository.records.size());
    }

    private CreateKnowledgeGapCommand command(
            String key, KnowledgeGapType type, UUID questionId, String question, String note
    ) {
        return CreateKnowledgeGapCommand.of("member", key, "atlas", "main", type, questionId, question, note);
    }

    private ProjectDetailView project(String branch) {
        return new ProjectDetailView(
                PROJECT_ID, "atlas", "Atlas", "", "Java", "main", branch,
                List.of(new BranchView(BRANCH_ID, branch, NOW, NOW, "admin", "admin")));
    }

    private static final class MemoryRepository implements KnowledgeGapFeedbackRepository {
        private final Map<String, KnowledgeGapFeedbackRecord> records = new LinkedHashMap<>();
        private final List<KnowledgeGapCitationRecord> citations = new ArrayList<>();

        @Override
        public boolean insertIfAbsent(KnowledgeGapFeedbackRecord feedback) {
            return records.putIfAbsent(feedback.operatorId() + ":" + feedback.idempotencyKey(), feedback) == null;
        }

        @Override public void insertCitations(List<KnowledgeGapCitationRecord> values) { citations.addAll(values); }
        @Override public Optional<KnowledgeGapFeedbackRecord> findByOperatorAndIdempotencyKey(String operatorId, String key) {
            return Optional.ofNullable(records.get(operatorId + ":" + key));
        }
        @Override public Optional<KnowledgeGapFeedbackRecord> findById(UUID feedbackId) { return Optional.empty(); }
        @Override public List<KnowledgeGapFeedbackRecord> findAll(KnowledgeGapFilter filter, io.github.loredock.knowledgegap.domain.KnowledgeGapCursor after, int limit) { return List.of(); }
        @Override public List<KnowledgeGapCitationRecord> findCitations(UUID feedbackId) {
            return citations.stream().filter(value -> value.feedbackId().equals(feedbackId)).toList();
        }
        @Override public boolean updateStatus(UUID feedbackId, io.github.loredock.knowledgegap.domain.KnowledgeGapStatus expected, io.github.loredock.knowledgegap.domain.KnowledgeGapStatus target, String actor, Instant updatedAt) { return false; }
    }
}
