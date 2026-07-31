package io.github.loredock.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.api.QaQuestionPage;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.converter.WebQaCursorCodec;
import io.github.loredock.qa.api.QaQuestionNotFoundException;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.snapshot.WebQaCursor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QaServiceQueryTest {
    private static final Long PROJECT_ID = 4937811932239626242L;
    private static final Long BRANCH_ID = 4937811932239626243L;
    private static final Instant NOW = Instant.parse("2026-07-30T05:00:00Z");
    private ProjectService projects;
    private AgentService agents;
    private WebQaQuestionDataService questions;
    private WebQaMessageDataService messages;
    private DefaultWebQaAssistantMessageMaterializer materializer;
    private QaServiceImpl service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        agents = mock(AgentService.class);
        questions = mock(WebQaQuestionDataService.class);
        messages = mock(WebQaMessageDataService.class);
        materializer = mock(DefaultWebQaAssistantMessageMaterializer.class);
        when(projects.resolveEnabledScope("atlas", null)).thenReturn(project());
        service = new QaServiceImpl(projects, agents, questions, messages, materializer, java.time.Clock.systemUTC());
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
        when(agents.get(any(), org.mockito.ArgumentMatchers.eq("member"))).thenAnswer(invocation ->
                run(invocation.getArgument(0)));
        when(messages.findByQuestionId(any())).thenReturn(List.of());

        QaQuestionPage page = service.history(new QaService.HistoryQuery(
                "member", "atlas", WebQaCursorCodec.encode(after), 2));

        assertThat(page.items()).extracting(QaQuestion::questionId)
                .containsExactly(first.id(), second.id());
        assertThat(WebQaCursorCodec.decode(page.nextCursor()))
                .isEqualTo(new WebQaCursor(second.createdAt(), second.id()));
        assertThat(page.items()).allSatisfy(value ->
                assertThat(value.trustState()).isEqualTo(QaQuestion.TrustState.RELIABLE_ANSWER));
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
        when(agents.get(question.runId(), "member")).thenReturn(run(question.runId()));
        when(messages.findByQuestionId(question.id())).thenReturn(List.of());
        when(materializer.materialize(any(), any())).thenThrow(new IllegalStateException("temporary projection"));

        QaQuestion snapshot = service.detail(
                new QaService.DetailQuery("member", "atlas", question.id()));

        assertThat(snapshot.trustState()).isEqualTo(QaQuestion.TrustState.RELIABLE_ANSWER);
        assertThat(snapshot.resultText()).isEqualTo("可信回答");
        assertThat(snapshot.messages()).isEmpty();
        System.out.printf("测试证据：场景=投影失败快照自愈，questionId=%s，Agent终态=%s，可信状态=%s%n",
                question.id(), snapshot.status(), snapshot.trustState());
    }

    /**
     * 业务目的：猜测其他操作者或其他项目的问答 ID 必须统一为不存在，不能泄露真实范围或运行标识。
     */
    @Test
    void missingOrScopeMismatchedDetailReturnsUniformNotFound() {
        Long guessed = 8000000000000000083L;
        when(questions.findVisibleById("member", PROJECT_ID, guessed)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(
                new QaService.DetailQuery("member", "atlas", guessed)))
                .isInstanceOf(QaQuestionNotFoundException.class)
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

    private AgentRun run(Long runId) {
        return new AgentRun(
                runId, AgentRun.Status.COMPLETED, AgentRun.ResultType.ANSWER, null,
                "可信回答", null, null,
                new AgentRun.Scope(PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null),
                0, 0, NOW.minusSeconds(1), NOW.minusSeconds(1), NOW, List.of());
    }
}
