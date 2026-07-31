package io.github.loredock.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.agent.service.AgentRunQueryService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.converter.WebQaCursorCodec;
import io.github.loredock.qa.exception.WebQaQuestionNotFoundException;
import io.github.loredock.qa.model.command.QueryWebQaDetailCommand;
import io.github.loredock.qa.model.command.QueryWebQaHistoryCommand;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.result.WebQaQuestionPage;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.snapshot.WebQaCursor;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryWebQaQuestionServiceTest {
    private static final Long PROJECT_ID = 4937811932239626242L;
    private static final Long BRANCH_ID = 4937811932239626243L;
    private static final Instant NOW = Instant.parse("2026-07-30T05:00:00Z");
    private ProjectService projects;
    private AgentRunQueryService runs;
    private WebQaQuestionDataService questions;
    private WebQaMessageDataService messages;
    private DefaultWebQaAssistantMessageMaterializer materializer;
    private QueryWebQaQuestionService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        runs = mock(AgentRunQueryService.class);
        questions = mock(WebQaQuestionDataService.class);
        messages = mock(WebQaMessageDataService.class);
        materializer = mock(DefaultWebQaAssistantMessageMaterializer.class);
        when(projects.resolveEnabledScope("atlas", null)).thenReturn(project());
        service = new QueryWebQaQuestionService(projects, runs, questions, messages, materializer);
    }

    /**
     * 业务目的：历史必须按数据库复合游标稳定分页，并只返回当前操作者和 URL 项目的记录。
     */
    @Test
    void historyUsesScopedCompositeCursorAndReturnsNextCursor() {
        WebQaQuestionRecord first = question(1, NOW, 4937811932239626258L);
        WebQaQuestionRecord second = question(2, NOW.minusSeconds(1),
                4937811932239626259L);
        WebQaQuestionRecord extra = question(3, NOW.minusSeconds(2),
                4937811932239626260L);
        WebQaCursor after = new WebQaCursor(NOW.plusSeconds(1), 8000000000000000081L);
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
        WebQaQuestionRecord question = question(1, NOW, 8000000000000000082L);
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
        Long guessed = 8000000000000000083L;
        when(questions.findVisibleById("member", PROJECT_ID, guessed)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(
                new QueryWebQaDetailCommand("member", "atlas", guessed)))
                .isInstanceOf(WebQaQuestionNotFoundException.class)
                .hasMessage("QA_QUESTION_NOT_FOUND");
        System.out.printf("测试证据：场景=详情防枚举，猜测questionId=%s，稳定错误=QA_QUESTION_NOT_FOUND%n", guessed);
    }

    private ProjectScope project() {
        return new ProjectScope(PROJECT_ID, "atlas", "Atlas", true, BRANCH_ID, "main");
    }

    private WebQaQuestionRecord question(int ordinal, Instant createdAt, Long id) {
        return new WebQaQuestionRecord(id, "member", "key-" + ordinal, "a".repeat(64),
                PROJECT_ID, "atlas", BRANCH_ID, "main",
                1000L + ordinal, createdAt);
    }

    private AgentRunSnapshot run(Long runId) {
        return new AgentRunSnapshot(
                runId, "member", "agent-key", "b".repeat(64), "project_qa", AgentRunStatus.COMPLETED,
                AgentResultType.ANSWER, "可信回答", null, null,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "fake", "v1"),
                4, 0, 0, null, null, NOW.minusSeconds(1), NOW.minusSeconds(1), NOW, List.of());
    }
}
