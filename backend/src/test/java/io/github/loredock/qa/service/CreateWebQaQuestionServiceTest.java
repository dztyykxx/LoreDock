package io.github.loredock.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.api.AgentRequestException;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.model.command.CreateWebQaQuestionCommand;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreateWebQaQuestionServiceTest {
    private static final Long PROJECT_ID = 6130197811678937090L;
    private static final Long BRANCH_ID = 6130197811678937091L;
    private static final Long RUN_ID = 6130197811678937092L;
    private static final Long RELEASE_BRANCH_ID = 6130197811678937093L;
    private static final Long QUESTION_ID = 6130197811678937094L;
    private static final Long MESSAGE_ID = 6130197811678937095L;
    private static final Instant NOW = Instant.parse("2026-07-30T03:00:00Z");
    private ProjectService projects;
    private AgentService agents;
    private WebQaQuestionDataService questions;
    private WebQaMessageDataService messages;
    private CreateWebQaQuestionService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        agents = mock(AgentService.class);
        questions = mock(WebQaQuestionDataService.class);
        messages = mock(WebQaMessageDataService.class);
        Clock timeProvider = mock(Clock.class);
        when(timeProvider.instant()).thenReturn(NOW);
        when(projects.resolveEnabledScope("atlas", null)).thenReturn(project());
        when(projects.resolveEnabledScope("atlas", "main")).thenReturn(project());
        when(questions.findByOperatorAndIdempotencyKey("member", "client-key"))
                .thenReturn(Optional.empty());
        when(questions.insertIfAbsent(any())).thenReturn(Optional.of(QUESTION_ID));
        when(messages.insertIfAbsent(any())).thenReturn(Optional.of(MESSAGE_ID));
        when(agents.start(any())).thenReturn(run());
        when(agents.get(RUN_ID, "member")).thenReturn(run());
        service = new CreateWebQaQuestionService(
                projects, agents, questions, messages, timeProvider);
    }

    /**
     * 业务目的：默认分支创建必须原子保存固定范围和唯一用户消息，且新问题绝不能拼接历史问答正文。
     */
    @Test
    void defaultMainCreationPassesOnlyCurrentQuestionAndPersistsUserMessage() {
        CreateWebQaQuestionCommand command = command("client-key", "本次新问题");

        WebQaQuestionSnapshot snapshot = service.create(command);

        ArgumentCaptor<AgentService.StartRequest> start = ArgumentCaptor.forClass(AgentService.StartRequest.class);
        verify(agents).start(start.capture());
        assertThat(start.getValue().branch()).isEqualTo("main");
        assertThat(start.getValue().question()).isEqualTo("本次新问题").doesNotContain("历史问题", "历史回答");
        ArgumentCaptor<WebQaQuestionRecord> question = ArgumentCaptor.forClass(WebQaQuestionRecord.class);
        verify(questions).insertIfAbsent(question.capture());
        assertThat(question.getValue().runId()).isEqualTo(RUN_ID);
        assertThat(question.getValue().projectId()).isEqualTo(PROJECT_ID);
        ArgumentCaptor<WebQaMessageRecord> message = ArgumentCaptor.forClass(WebQaMessageRecord.class);
        verify(messages).insertIfAbsent(message.capture());
        assertThat(message.getValue().role()).isEqualTo(WebQaMessageRole.USER);
        assertThat(message.getValue().content()).isEqualTo("本次新问题");
        assertThat(snapshot.trustState()).isEqualTo(WebQaTrustState.IN_PROGRESS);
        System.out.printf("测试证据：场景=默认main创建，questionId=%s，runId=%s，消息角色=%s，模型输入字符=%d%n",
                snapshot.question().id(), snapshot.run().runId(), message.getValue().role(),
                start.getValue().question().codePointCount(0, start.getValue().question().length()));
    }

    /**
     * 业务目的：显式分支必须原样传给 Agent 并固化运行解析后的分支，防止问答悄悄落到默认 main。
     */
    @Test
    void explicitBranchCreationKeepsResolvedScope() {
        ProjectScope releaseProject = project("release", RELEASE_BRANCH_ID);
        AgentRun releaseRun = run("release", RELEASE_BRANCH_ID);
        when(projects.resolveEnabledScope("atlas", "release")).thenReturn(releaseProject);
        when(agents.start(any())).thenReturn(releaseRun);
        when(agents.get(RUN_ID, "member")).thenReturn(releaseRun);

        WebQaQuestionSnapshot snapshot = service.create(CreateWebQaQuestionCommand.of(
                "member", "MEMBER", "release-key", "atlas", "release", "发布分支如何实现？"));

        ArgumentCaptor<AgentService.StartRequest> start = ArgumentCaptor.forClass(AgentService.StartRequest.class);
        verify(agents).start(start.capture());
        assertThat(start.getValue().branch()).isEqualTo("release");
        assertThat(snapshot.question().branchId()).isEqualTo(RELEASE_BRANCH_ID);
        assertThat(snapshot.question().branch()).isEqualTo("release");
        System.out.printf("测试证据：场景=显式分支创建，project=%s，branch=%s，branchId=%s，runId=%s%n",
                snapshot.question().projectIdentifier(), snapshot.question().branch(),
                snapshot.question().branchId(), snapshot.run().runId());
    }

    /**
     * 业务目的：相同操作者以相同键重试相同输入必须复用原问答，不重复启动模型或保存消息。
     */
    @Test
    void identicalRetryReturnsOriginalQuestionWithoutSideEffects() {
        WebQaQuestionSnapshot first = service.create(command("client-key", "本次新问题"));
        when(questions.findByOperatorAndIdempotencyKey("member", "client-key"))
                .thenReturn(Optional.of(first.question()));
        when(messages.findByQuestionId(first.question().id())).thenReturn(first.messages());

        WebQaQuestionSnapshot retried = service.create(command("client-key", "本次新问题"));

        assertThat(retried.question().id()).isEqualTo(first.question().id());
        verify(agents, org.mockito.Mockito.times(1)).start(any());
        verify(questions, org.mockito.Mockito.times(1)).insertIfAbsent(any());
        verify(messages, org.mockito.Mockito.times(1)).insertIfAbsent(any());
        System.out.printf("测试证据：场景=问答幂等复用，questionId=%s，runId=%s，模型启动次数=1%n",
                retried.question().id(), retried.run().runId());
    }

    /**
     * 业务目的：相同幂等键对应不同问题必须在启动 Agent 前冲突，防止覆盖原历史或产生第二个运行。
     */
    @Test
    void changedInputWithSameKeyConflictsBeforeAgentStart() {
        WebQaQuestionRecord existing = new WebQaQuestionRecord(
                8000000000000000080L, "member", "client-key", "0".repeat(64), PROJECT_ID, "atlas",
                BRANCH_ID, "main", RUN_ID, NOW);
        when(questions.findByOperatorAndIdempotencyKey("member", "client-key"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(command("client-key", "不同问题")))
                .isInstanceOf(AgentRequestException.class);

        verify(agents, never()).start(any());
        verify(questions, never()).insertIfAbsent(any());
        verify(messages, never()).insertIfAbsent(any());
        System.out.printf("测试证据：场景=问答幂等冲突，原questionId=%s，新增运行数=0，新增消息数=0%n",
                existing.id());
    }

    /**
     * 业务目的：项目或分支解析失败必须发生在任何业务写入前，防止留下无范围的问答或孤立消息。
     */
    @Test
    void invalidProjectOrBranchFailsBeforePersistence() {
        when(projects.resolveEnabledScope("atlas", "missing"))
                .thenThrow(new IllegalArgumentException("branch missing"));

        assertThatThrownBy(() -> service.create(CreateWebQaQuestionCommand.of(
                "member", "MEMBER", "client-key", "atlas", "missing", "问题")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(agents, never()).start(any());
        verify(questions, never()).insertIfAbsent(any());
        verify(messages, never()).insertIfAbsent(any());
        System.out.println("测试证据：场景=无效分支，Agent启动=0，问答写入=0，消息写入=0");
    }

    private CreateWebQaQuestionCommand command(String key, String question) {
        return CreateWebQaQuestionCommand.of("member", "MEMBER", key, "atlas", null, question);
    }

    private ProjectScope project() {
        return project("main", BRANCH_ID);
    }

    private ProjectScope project(String selectedBranch, Long selectedBranchId) {
        return new ProjectScope(PROJECT_ID, "atlas", "Atlas", true, selectedBranchId, selectedBranch);
    }

    private AgentRun run() {
        return run("main", BRANCH_ID);
    }

    private AgentRun run(String branch, Long branchId) {
        return new AgentRun(
                RUN_ID, AgentRun.Status.ACCEPTED, null, null, null, null, null,
                new AgentRun.Scope(PROJECT_ID, "atlas", branchId, branch, null, null, null),
                0, 0, NOW, null, null, List.of());
    }
}
