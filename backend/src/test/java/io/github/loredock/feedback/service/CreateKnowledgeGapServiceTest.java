package io.github.loredock.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.feedback.exception.KnowledgeGapIdempotencyConflictException;
import io.github.loredock.feedback.model.command.CreateKnowledgeGapCommand;
import io.github.loredock.feedback.model.enums.KnowledgeGapType;
import io.github.loredock.feedback.model.result.KnowledgeGapCitationRecord;
import io.github.loredock.feedback.model.result.KnowledgeGapFeedbackRecord;
import io.github.loredock.feedback.model.result.KnowledgeGapFilter;
import io.github.loredock.feedback.model.snapshot.KnowledgeGapFeedbackSnapshot;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.exception.WebQaQuestionNotFoundException;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import io.github.loredock.qa.service.QueryWebQaQuestionService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateKnowledgeGapServiceTest {
    private static final Long PROJECT_ID = 2553040173361004546L;
    private static final Long BRANCH_ID = 2553040173361004547L;
    private static final Long QUESTION_ID = 2553040173361004548L;
    private static final Long RUN_ID = 2553040173361004549L;
    private static final Long EVIDENCE_ID = 2553040173361004550L;
    private static final Long FEEDBACK_ID = 2553040173361004551L;
    private static final Instant NOW = Instant.parse("2026-07-30T11:00:00Z");

    private ProjectService projects;
    private QueryWebQaQuestionService questions;
    private MemoryRepository repository;
    private CreateKnowledgeGapService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        questions = mock(QueryWebQaQuestionService.class);
        repository = new MemoryRepository();
        Clock time = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        service = new CreateKnowledgeGapService(projects, questions, repository, time);
        when(projects.resolveEnabledScope("atlas", "main")).thenReturn(project("main"));
        when(projects.resolveEnabledScope("atlas", null)).thenReturn(project("main"));
    }

    /**
     * 业务目的：关联问答反馈必须复制服务端问题、运行终态和引用，忽略客户端伪造问题且不复制答案正文。
     */
    @Test
    void linkedQuestionCopiesOnlyServerFactsAndCitations() {
        AgentRun run = mock(AgentRun.class);
        AgentRun.Citation citation = mock(AgentRun.Citation.class);
        when(citation.evidenceId()).thenReturn(EVIDENCE_ID);
        when(run.runId()).thenReturn(RUN_ID);
        when(run.resultType()).thenReturn(AgentRun.ResultType.ANSWER);
        when(run.errorCode()).thenReturn(null);
        when(run.citations()).thenReturn(List.of(citation));
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                QUESTION_ID, "member", "qa-key", "a".repeat(64), PROJECT_ID, "atlas", BRANCH_ID,
                "main", RUN_ID, NOW.minusSeconds(10));
        WebQaMessageRecord user = new WebQaMessageRecord(
                8000000000000000054L, QUESTION_ID, WebQaMessageRole.USER, "服务端真实问题", null, null, NOW);
        WebQaMessageRecord assistant = new WebQaMessageRecord(
                8000000000000000055L, QUESTION_ID, WebQaMessageRole.ASSISTANT, "不得复制的完整答案",
                AgentRun.ResultType.ANSWER, null, NOW);
        when(questions.detail(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WebQaQuestionSnapshot(question, run, null, List.of(user, assistant)));

        KnowledgeGapFeedbackSnapshot result = service.create(command(
                "linked-key", KnowledgeGapType.WRONG_ANSWER, QUESTION_ID, "客户端伪造问题", "补充"));

        assertThat(result.feedback().question()).isEqualTo("服务端真实问题");
        assertThat(result.feedback().runId()).isEqualTo(RUN_ID);
        assertThat(result.feedback().resultType()).isEqualTo(AgentRun.ResultType.ANSWER);
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
        AgentRun run = mock(AgentRun.class);
        when(run.runId()).thenReturn(RUN_ID);
        when(run.citations()).thenReturn(List.of());
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                QUESTION_ID, "member", "qa-key", "a".repeat(64), PROJECT_ID, "atlas", 8000000000000000056L,
                "release", RUN_ID, NOW.minusSeconds(10));
        WebQaMessageRecord user = new WebQaMessageRecord(
                8000000000000000057L, QUESTION_ID, WebQaMessageRole.USER, "服务端真实问题", null, null, NOW);
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
            String key, KnowledgeGapType type, Long questionId, String question, String note
    ) {
        return CreateKnowledgeGapCommand.of("member", key, "atlas", "main", type, questionId, question, note);
    }

    private ProjectScope project(String branch) {
        return new ProjectScope(PROJECT_ID, "atlas", "Atlas", true, BRANCH_ID, branch);
    }

    private static final class MemoryRepository extends KnowledgeGapDataService {
        private final Map<String, KnowledgeGapFeedbackRecord> records = new LinkedHashMap<>();
        private final List<KnowledgeGapCitationRecord> citations = new ArrayList<>();

        private MemoryRepository() {
            super(null, null);
        }

        @Override
        public Optional<Long> insertIfAbsent(KnowledgeGapFeedbackRecord feedback) {
            String key = feedback.operatorId() + ":" + feedback.idempotencyKey();
            KnowledgeGapFeedbackRecord stored = new KnowledgeGapFeedbackRecord(
                    FEEDBACK_ID, feedback.operatorId(), feedback.idempotencyKey(), feedback.requestHash(),
                    feedback.projectId(), feedback.projectIdentifier(), feedback.branchId(), feedback.branch(),
                    feedback.questionId(), feedback.runId(), feedback.type(), feedback.status(), feedback.question(),
                    feedback.note(), feedback.resultType(), feedback.refusalReason(), feedback.errorCode(),
                    feedback.createdAt(), feedback.updatedAt(), feedback.createdBy(), feedback.updatedBy());
            return records.putIfAbsent(key, stored) == null ? Optional.of(FEEDBACK_ID) : Optional.empty();
        }

        @Override public void insertCitations(List<KnowledgeGapCitationRecord> values) { citations.addAll(values); }
        @Override public Optional<KnowledgeGapFeedbackRecord> findByOperatorAndIdempotencyKey(String operatorId, String key) {
            return Optional.ofNullable(records.get(operatorId + ":" + key));
        }
        @Override public Optional<KnowledgeGapFeedbackRecord> findById(Long feedbackId) { return Optional.empty(); }
        @Override public List<KnowledgeGapFeedbackRecord> findAll(KnowledgeGapFilter filter, io.github.loredock.feedback.model.snapshot.KnowledgeGapCursor after, int limit) { return List.of(); }
        @Override public List<KnowledgeGapCitationRecord> findCitations(Long feedbackId) {
            return citations.stream().filter(value -> value.feedbackId().equals(feedbackId)).toList();
        }
        @Override public boolean updateStatus(Long feedbackId, io.github.loredock.feedback.model.enums.KnowledgeGapStatus expected, io.github.loredock.feedback.model.enums.KnowledgeGapStatus target, String actor, Instant updatedAt) { return false; }
    }
}
