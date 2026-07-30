package io.github.loredock.qa.application;

import io.github.loredock.agent.application.AgentRunQueryUseCase;
import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.project.application.BranchView;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import io.github.loredock.qa.domain.WebQaCursor;
import io.github.loredock.qa.domain.WebQaCursorCodec;
import io.github.loredock.qa.domain.WebQaTrustState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryWebQaQuestionServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("73000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_ID = UUID.fromString("73000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-07-30T05:00:00Z");
    private ProjectQueryUseCase projects;
    private AgentRunQueryUseCase runs;
    private WebQaQuestionRepository questions;
    private WebQaMessageRepository messages;
    private WebQaAssistantMessageMaterializer materializer;
    private QueryWebQaQuestionService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectQueryUseCase.class);
        runs = mock(AgentRunQueryUseCase.class);
        questions = mock(WebQaQuestionRepository.class);
        messages = mock(WebQaMessageRepository.class);
        materializer = mock(WebQaAssistantMessageMaterializer.class);
        when(projects.getEnabledProject("atlas", null)).thenReturn(project());
        service = new QueryWebQaQuestionService(projects, runs, questions, messages, materializer);
    }

    /**
     * 业务目的：历史必须按数据库复合游标稳定分页，并只返回当前操作者和 URL 项目的记录。
     */
    @Test
    void historyUsesScopedCompositeCursorAndReturnsNextCursor() {
        WebQaQuestionRecord first = question(1, NOW, UUID.fromString("73000000-0000-0000-0000-000000000011"));
        WebQaQuestionRecord second = question(2, NOW.minusSeconds(1),
                UUID.fromString("73000000-0000-0000-0000-000000000012"));
        WebQaQuestionRecord extra = question(3, NOW.minusSeconds(2),
                UUID.fromString("73000000-0000-0000-0000-000000000013"));
        WebQaCursor after = new WebQaCursor(NOW.plusSeconds(1), UUID.randomUUID());
        when(questions.findHistory("member", PROJECT_ID, after, 3))
                .thenReturn(List.of(first, second, extra));
        when(runs.get(any(), org.mockito.ArgumentMatchers.eq("member"))).thenAnswer(invocation ->
                run(invocation.getArgument(0)));
        when(messages.findByQuestionId(any())).thenReturn(List.of());

        WebQaQuestionPage page = service.history(new QueryWebQaHistoryCommand(
                "member", "atlas", WebQaCursorCodec.encode(after), 2));

        assertThat(page.items()).extracting(value -> value.question().id())
                .containsExactly(first.id(), second.id());
        assertThat(WebQaCursorCodec.decode(page.nextCursor()))
                .isEqualTo(new WebQaCursor(second.createdAt(), second.id()));
        assertThat(page.items()).allSatisfy(value ->
                assertThat(value.trustState()).isEqualTo(WebQaTrustState.RELIABLE_ANSWER));
        System.out.printf("测试证据：场景=历史复合游标，项目=atlas，返回=%d，额外探测=1，下一游标ID=%s%n",
                page.items().size(), second.id());
    }

    /**
     * 业务目的：终态消息投影暂时失败时详情仍必须从 Agent 事实返回正确可信状态，下次读取可以继续自愈。
     */
    @Test
    void detailUsesAgentFactWhenMessageMaterializationFails() {
        WebQaQuestionRecord question = question(1, NOW, UUID.randomUUID());
        when(questions.findVisibleById("member", PROJECT_ID, question.id()))
                .thenReturn(Optional.of(question));
        when(runs.get(question.runId(), "member")).thenReturn(run(question.runId()));
        when(messages.findByQuestionId(question.id())).thenReturn(List.of());
        when(materializer.materialize(any(), any())).thenThrow(new IllegalStateException("temporary projection"));

        WebQaQuestionSnapshot snapshot = service.detail(
                new QueryWebQaDetailCommand("member", "atlas", question.id()));

        assertThat(snapshot.trustState()).isEqualTo(WebQaTrustState.RELIABLE_ANSWER);
        assertThat(snapshot.run().resultText()).isEqualTo("可信回答");
        assertThat(snapshot.messages()).isEmpty();
        System.out.printf("测试证据：场景=投影失败快照自愈，questionId=%s，Agent终态=%s，可信状态=%s%n",
                question.id(), snapshot.run().status(), snapshot.trustState());
    }

    /**
     * 业务目的：猜测其他操作者或其他项目的问答 ID 必须统一为不存在，不能泄露真实范围或运行标识。
     */
    @Test
    void missingOrScopeMismatchedDetailReturnsUniformNotFound() {
        UUID guessed = UUID.randomUUID();
        when(questions.findVisibleById("member", PROJECT_ID, guessed)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(
                new QueryWebQaDetailCommand("member", "atlas", guessed)))
                .isInstanceOf(WebQaQuestionNotFoundException.class)
                .hasMessage("QA_QUESTION_NOT_FOUND");
        System.out.printf("测试证据：场景=详情防枚举，猜测questionId=%s，稳定错误=QA_QUESTION_NOT_FOUND%n", guessed);
    }

    private ProjectDetailView project() {
        BranchView branch = new BranchView(BRANCH_ID, "main", NOW, NOW, "admin", "admin");
        return new ProjectDetailView(PROJECT_ID, "atlas", "Atlas", "", "Java", "main", "main", List.of(branch));
    }

    private WebQaQuestionRecord question(int ordinal, Instant createdAt, UUID id) {
        return new WebQaQuestionRecord(id, "member", "key-" + ordinal, "a".repeat(64),
                PROJECT_ID, "atlas", BRANCH_ID, "main",
                UUID.nameUUIDFromBytes(("run-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)), createdAt);
    }

    private AgentRunSnapshot run(UUID runId) {
        return new AgentRunSnapshot(
                runId, "member", "agent-key", "b".repeat(64), "project_qa", AgentRunStatus.COMPLETED,
                AgentResultType.ANSWER, "可信回答", null, null,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "c".repeat(64),
                        "fake", "fake", "v1", "v1", "v1"),
                4, 0, 0, null, null, NOW.minusSeconds(1), NOW.minusSeconds(1), NOW, List.of());
    }
}
