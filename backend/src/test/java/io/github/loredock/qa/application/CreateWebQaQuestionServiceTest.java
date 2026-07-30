package io.github.loredock.qa.application;

import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.application.AgentRunQueryUseCase;
import io.github.loredock.agent.application.AgentRequestException;
import io.github.loredock.agent.application.StartProjectQaRunCommand;
import io.github.loredock.agent.application.StartProjectQaRunUseCase;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.project.application.BranchView;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import io.github.loredock.qa.domain.WebQaMessageRole;
import io.github.loredock.qa.domain.WebQaTrustState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateWebQaQuestionServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_ID = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID RELEASE_BRANCH_ID = UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-07-30T03:00:00Z");
    private ProjectQueryUseCase projects;
    private StartProjectQaRunUseCase starts;
    private AgentRunQueryUseCase runQueries;
    private WebQaQuestionRepository questions;
    private WebQaMessageRepository messages;
    private CreateWebQaQuestionService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectQueryUseCase.class);
        starts = mock(StartProjectQaRunUseCase.class);
        runQueries = mock(AgentRunQueryUseCase.class);
        questions = mock(WebQaQuestionRepository.class);
        messages = mock(WebQaMessageRepository.class);
        TimeProvider timeProvider = mock(TimeProvider.class);
        when(timeProvider.now()).thenReturn(NOW);
        when(projects.getEnabledProject("atlas", null)).thenReturn(project());
        when(projects.getEnabledProject("atlas", "main")).thenReturn(project());
        when(questions.findByOperatorAndIdempotencyKey("member", "client-key"))
                .thenReturn(Optional.empty());
        when(questions.insertIfAbsent(any())).thenReturn(true);
        when(messages.insertIfAbsent(any())).thenReturn(true);
        when(starts.start(any())).thenReturn(run());
        when(runQueries.get(RUN_ID, "member")).thenReturn(run());
        service = new CreateWebQaQuestionService(
                projects, starts, runQueries, questions, messages, timeProvider);
    }

    /**
     * 业务目的：默认分支创建必须原子保存固定范围和唯一用户消息，且新问题绝不能拼接历史问答正文。
     */
    @Test
    void defaultMainCreationPassesOnlyCurrentQuestionAndPersistsUserMessage() {
        CreateWebQaQuestionCommand command = command("client-key", "本次新问题");

        WebQaQuestionSnapshot snapshot = service.create(command);

        ArgumentCaptor<StartProjectQaRunCommand> start = ArgumentCaptor.forClass(StartProjectQaRunCommand.class);
        verify(starts).start(start.capture());
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
        ProjectDetailView releaseProject = project("release", RELEASE_BRANCH_ID);
        AgentRunSnapshot releaseRun = run("release", RELEASE_BRANCH_ID);
        when(projects.getEnabledProject("atlas", "release")).thenReturn(releaseProject);
        when(starts.start(any())).thenReturn(releaseRun);
        when(runQueries.get(RUN_ID, "member")).thenReturn(releaseRun);

        WebQaQuestionSnapshot snapshot = service.create(CreateWebQaQuestionCommand.of(
                "member", "MEMBER", "release-key", "atlas", "release", "发布分支如何实现？"));

        ArgumentCaptor<StartProjectQaRunCommand> start = ArgumentCaptor.forClass(StartProjectQaRunCommand.class);
        verify(starts).start(start.capture());
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
        verify(starts, org.mockito.Mockito.times(1)).start(any());
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
                UUID.randomUUID(), "member", "client-key", "0".repeat(64), PROJECT_ID, "atlas",
                BRANCH_ID, "main", RUN_ID, NOW);
        when(questions.findByOperatorAndIdempotencyKey("member", "client-key"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(command("client-key", "不同问题")))
                .isInstanceOf(AgentRequestException.class);

        verify(starts, never()).start(any());
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
        when(projects.getEnabledProject("atlas", "missing"))
                .thenThrow(new IllegalArgumentException("branch missing"));

        assertThatThrownBy(() -> service.create(CreateWebQaQuestionCommand.of(
                "member", "MEMBER", "client-key", "atlas", "missing", "问题")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(starts, never()).start(any());
        verify(questions, never()).insertIfAbsent(any());
        verify(messages, never()).insertIfAbsent(any());
        System.out.println("测试证据：场景=无效分支，Agent启动=0，问答写入=0，消息写入=0");
    }

    private CreateWebQaQuestionCommand command(String key, String question) {
        return CreateWebQaQuestionCommand.of("member", "MEMBER", key, "atlas", null, question);
    }

    private ProjectDetailView project() {
        return project("main", BRANCH_ID);
    }

    private ProjectDetailView project(String selectedBranch, UUID selectedBranchId) {
        BranchView branch = new BranchView(selectedBranchId, selectedBranch, NOW, NOW, "admin", "admin");
        return new ProjectDetailView(
                PROJECT_ID, "atlas", "Atlas", "", "Java", "main", selectedBranch, List.of(branch));
    }

    private AgentRunSnapshot run() {
        return run("main", BRANCH_ID);
    }

    private AgentRunSnapshot run(String branch, UUID branchId) {
        return new AgentRunSnapshot(
                RUN_ID, "member", "agent-key", "a".repeat(64), "project_qa", AgentRunStatus.ACCEPTED,
                null, null, null, null,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", branchId, branch, null, null, null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "b".repeat(64),
                        "fake", "fake-model", "project-qa-v1", "readonly-v1", "limits-v1"),
                5, 0, 0, null, null, NOW, null, null, List.of());
    }
}
