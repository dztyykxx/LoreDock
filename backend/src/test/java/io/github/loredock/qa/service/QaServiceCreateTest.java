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
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.api.QaConversationBusyException;
import io.github.loredock.qa.api.QaConversationNotFoundException;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaConversationRecord;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QaServiceCreateTest {
    private static final Long PROJECT_ID = 6130197811678937090L;
    private static final Long BRANCH_ID = 6130197811678937091L;
    private static final Long RUN_ID = 6130197811678937092L;
    private static final Long RELEASE_BRANCH_ID = 6130197811678937093L;
    private static final Long QUESTION_ID = 6130197811678937094L;
    private static final Long MESSAGE_ID = 6130197811678937095L;
    private static final Instant NOW = Instant.parse("2026-07-30T03:00:00Z");
    private ProjectService projects;
    private AgentService agents;
    private WebQaConversationDataService conversations;
    private WebQaQuestionDataService questions;
    private WebQaMessageDataService messages;
    private QaServiceImpl service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        agents = mock(AgentService.class);
        conversations = mock(WebQaConversationDataService.class);
        questions = mock(WebQaQuestionDataService.class);
        messages = mock(WebQaMessageDataService.class);
        Clock timeProvider = mock(Clock.class);
        when(timeProvider.instant()).thenReturn(NOW);
        when(projects.resolveEnabledScope("atlas", null)).thenReturn(project());
        when(projects.resolveEnabledScope("atlas", "main")).thenReturn(project());
        when(questions.findByOperatorAndIdempotencyKey("member", "client-key"))
                .thenReturn(Optional.empty());
        when(conversations.insert(any())).thenReturn(conversation(7000000000000000001L));
        when(conversations.lockVisible(any(), any(), any())).thenReturn(
                Optional.of(conversation(7000000000000000001L)));
        when(questions.hasActiveRound(any())).thenReturn(false);
        when(questions.insertIfAbsent(any())).thenReturn(Optional.of(QUESTION_ID));
        when(messages.insertIfAbsent(any())).thenReturn(Optional.of(MESSAGE_ID));
        when(agents.start(any())).thenReturn(run());
        when(agents.get(RUN_ID, "member")).thenReturn(run());
        service = new QaServiceImpl(
                projects, agents, conversations, questions, messages,
                mock(DefaultWebQaAssistantMessageMaterializer.class), timeProvider);
    }

    /**
     * 业务目的：省略 conversationId 时必须创建归属于当前操作者和项目的新会话，并把稳定会话 ID 返回给客户端。
     */
    @Test
    void omittedConversationCreatesOwnedConversationAndReturnsItsId() {
        WebQaQuestionSnapshot snapshot = service.createSnapshot(command("client-key", "为什么这样设计？"));

        verify(conversations).insert(any());
        assertThat(snapshot.question().conversationId()).isEqualTo(7000000000000000001L);
        System.out.printf("测试证据：场景=创建首轮会话，conversationId=%s，questionId=%s，project=atlas%n",
                snapshot.question().conversationId(), snapshot.question().id());
    }

    /**
     * 业务目的：猜测其他操作者、其他项目或不存在的会话必须统一 404，且模型和问题写入均不能发生。
     */
    @Test
    void invisibleConversationIsNotFoundBeforeAgentOrQuestionCreation() {
        when(conversations.lockVisible(7000000000000000002L, "member", PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSnapshot(new QaService.CreateRequest(
                "member", "MEMBER", "foreign-conversation-key", "atlas", null,
                7000000000000000002L, "继续追问")))
                .isInstanceOf(QaConversationNotFoundException.class);

        verify(agents, never()).start(any());
        verify(questions, never()).insertIfAbsent(any());
        System.out.println("测试证据：场景=会话归属隔离，隐藏会话=404，Agent启动=0，问题写入=0");
    }

    /**
     * 业务目的：同一会话存在活动轮次时必须返回 409，防止两个运行读取同一历史后产生分叉顺序。
     */
    @Test
    void activeConversationRejectsConcurrentRound() {
        when(questions.hasActiveRound(7000000000000000001L)).thenReturn(true);

        assertThatThrownBy(() -> service.createSnapshot(new QaService.CreateRequest(
                "member", "MEMBER", "busy-conversation-key", "atlas", null,
                7000000000000000001L, "并发追问")))
                .isInstanceOf(QaConversationBusyException.class);

        verify(agents, never()).start(any());
        verify(questions, never()).insertIfAbsent(any());
        System.out.println("测试证据：场景=活动会话互斥，HTTP语义=409，新增轮次=0");
    }

    /**
     * 业务目的：继续追问只能注入同会话已完成的问题和最终回答，即使助手消息尚未投影也应从运行终态补齐，失败轮次不得混入。
     */
    @Test
    void followUpUsesCompletedResultAndExcludesFailedRound() {
        Long conversationId = 7000000000000000001L;
        WebQaQuestionRecord completedQuestion = previousQuestion(7000000000000000011L, conversationId, RUN_ID + 10);
        WebQaQuestionRecord failedQuestion = previousQuestion(7000000000000000012L, conversationId, RUN_ID + 11);
        when(questions.findByConversation(conversationId, 101)).thenReturn(List.of(completedQuestion, failedQuestion));
        when(agents.get(RUN_ID + 10, "member")).thenReturn(completedRun(RUN_ID + 10, "已校验的上一轮回答"));
        when(agents.get(RUN_ID + 11, "member")).thenReturn(failedRun(RUN_ID + 11));
        when(messages.findByQuestionId(completedQuestion.id())).thenReturn(List.of(new WebQaMessageRecord(
                7000000000000000021L, completedQuestion.id(), WebQaMessageRole.USER, "上一轮问题",
                null, null, NOW.minusSeconds(30))));
        when(messages.findByQuestionId(failedQuestion.id())).thenReturn(List.of(new WebQaMessageRecord(
                7000000000000000022L, failedQuestion.id(), WebQaMessageRole.USER, "失败轮次的私有问题",
                null, null, NOW.minusSeconds(20))));

        service.createSnapshot(new QaService.CreateRequest(
                "member", "MEMBER", "follow-key", "atlas", null, conversationId, "它还有哪些限制？"));

        ArgumentCaptor<AgentService.StartRequest> start = ArgumentCaptor.forClass(AgentService.StartRequest.class);
        verify(agents).start(start.capture());
        assertThat(start.getValue().conversationHistory()).extracting(AgentService.ConversationMessage::content)
                .containsExactly("上一轮问题", "已校验的上一轮回答")
                .doesNotContain("失败轮次的私有问题");
        System.out.println("测试证据：场景=追问历史筛选，已完成消息=2，失败轮次=0，终态回答补齐=true");
    }

    /**
     * 业务目的：默认分支创建必须原子保存固定范围和唯一用户消息，且新问题绝不能拼接历史问答正文。
     */
    @Test
    void defaultMainCreationPassesOnlyCurrentQuestionAndPersistsUserMessage() {
        QaService.CreateRequest command = command("client-key", "本次新问题");

        WebQaQuestionSnapshot snapshot = service.createSnapshot(command);

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

        WebQaQuestionSnapshot snapshot = service.createSnapshot(new QaService.CreateRequest(
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
        WebQaQuestionSnapshot first = service.createSnapshot(command("client-key", "本次新问题"));
        when(questions.findByOperatorAndIdempotencyKey("member", "client-key"))
                .thenReturn(Optional.of(first.question()));
        when(messages.findByQuestionId(first.question().id())).thenReturn(first.messages());

        WebQaQuestionSnapshot retried = service.createSnapshot(command("client-key", "本次新问题"));

        assertThat(retried.question().id()).isEqualTo(first.question().id());
        verify(agents, org.mockito.Mockito.times(1)).start(any());
        verify(questions, org.mockito.Mockito.times(1)).insertIfAbsent(any());
        verify(messages, org.mockito.Mockito.times(1)).insertIfAbsent(any());
        System.out.printf("测试证据：场景=问答幂等复用，questionId=%s，runId=%s，模型启动次数=1%n",
                retried.question().id(), retried.run().runId());
    }

    /**
     * 业务目的：两个首轮请求竞争同一幂等键时，输家必须删除自己未绑定问题的新会话，防止最近会话出现空记录。
     */
    @Test
    void idempotentRaceDeletesLosingEmptyConversation() {
        WebQaQuestionRecord winner = new WebQaQuestionRecord(
                QUESTION_ID, 7000000000000000002L, "member", "client-key", requestHash(null, "本次新问题"),
                PROJECT_ID, "atlas", BRANCH_ID, "main", RUN_ID, NOW);
        when(questions.insertIfAbsent(any())).thenReturn(Optional.empty());
        when(questions.findByOperatorAndIdempotencyKey("member", "client-key"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(messages.findByQuestionId(QUESTION_ID)).thenReturn(List.of());

        WebQaQuestionSnapshot result = service.createSnapshot(command("client-key", "本次新问题"));

        verify(conversations).deleteEmpty(7000000000000000001L);
        assertThat(result.question().conversationId()).isEqualTo(7000000000000000002L);
        System.out.printf("测试证据：场景=首轮幂等竞争，输家空会话=%s，胜者会话=%s%n",
                7000000000000000001L, result.question().conversationId());
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

        assertThatThrownBy(() -> service.createSnapshot(command("client-key", "不同问题")))
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

        assertThatThrownBy(() -> service.createSnapshot(new QaService.CreateRequest(
                "member", "MEMBER", "client-key", "atlas", "missing", "问题")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(agents, never()).start(any());
        verify(questions, never()).insertIfAbsent(any());
        verify(messages, never()).insertIfAbsent(any());
        System.out.println("测试证据：场景=无效分支，Agent启动=0，问答写入=0，消息写入=0");
    }

    private QaService.CreateRequest command(String key, String question) {
        return new QaService.CreateRequest("member", "MEMBER", key, "atlas", null, question);
    }

    private String requestHash(Long conversationId, String question) {
        try {
            String value = "atlas\nmain\n" + (conversationId == null ? "NEW" : conversationId) + "\n" + question;
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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

    private AgentRun completedRun(Long runId, String answer) {
        return new AgentRun(runId, AgentRun.Status.COMPLETED, AgentRun.ResultType.ANSWER,
                AgentRun.AnswerBasis.BUSINESS_RULE, answer, null, null,
                new AgentRun.Scope(PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null),
                2, 1, NOW.minusSeconds(40), NOW.minusSeconds(35), NOW.minusSeconds(25), List.of());
    }

    private AgentRun failedRun(Long runId) {
        return new AgentRun(runId, AgentRun.Status.FAILED, null, null, null, null,
                AgentRun.ErrorCode.AGENT_MODEL_UNAVAILABLE,
                new AgentRun.Scope(PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null),
                1, 1, NOW.minusSeconds(25), NOW.minusSeconds(20), NOW.minusSeconds(15), List.of());
    }

    private WebQaQuestionRecord previousQuestion(Long questionId, Long conversationId, Long runId) {
        return new WebQaQuestionRecord(questionId, conversationId, "member", "previous-" + questionId,
                "0".repeat(64), PROJECT_ID, "atlas", BRANCH_ID, "main", runId, NOW.minusSeconds(30));
    }

    private WebQaConversationRecord conversation(Long id) {
        return new WebQaConversationRecord(id, "member", PROJECT_ID, "atlas", "为什么这样设计？", NOW, NOW, NOW);
    }
}
