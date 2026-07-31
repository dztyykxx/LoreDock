package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.exception.AgentRequestException;
import io.github.loredock.agent.model.command.AgentRunCreateData;
import io.github.loredock.agent.model.command.StartProjectQaRunCommand;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentRunAcceptanceResult;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.scheduler.BoundedAgentRunScheduler;
import io.github.loredock.agent.skill.AgentDefinition;
import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.model.result.ActiveCodeSnapshotView;
import io.github.loredock.code.service.ActiveCodeSnapshotQueryService;
import io.github.loredock.knowledge.model.result.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.service.KnowledgeSearchIndexDataService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StartProjectQaRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");
    private static final Long PROJECT_ID = 2891640495451214098L;
    private static final Long BRANCH_ID = 916404954512140971L;
    private static final Long SNAPSHOT_ID = 5783280990902428195L;
    private static final Long GENERATION_ID = 1674921486353642292L;
    private static final Long RUN_ID = 1674921486353642293L;
    private AgentProperties configuration;
    private AgentDefinitionProvider definitions;
    private ProjectService projects;
    private ActiveCodeSnapshotQueryService code;
    private KnowledgeSearchIndexDataService knowledge;
    private AgentRunService runs;
    private BoundedAgentRunScheduler scheduler;
    private TransactionAwareAgentRunDispatchCoordinator dispatch;
    private PersistentAgentRunDispatchFailureHandler dispatchFailures;
    private AtomicReference<AgentRunCreateData> acceptedData;
    private AtomicReference<AgentExecutionRequest> scheduledRequest;

    @BeforeEach
    void setUp() {
        configuration = configuration(true, true, "model-v1");
        definitions = mock(AgentDefinitionProvider.class);
        projects = mock(ProjectService.class);
        code = mock(ActiveCodeSnapshotQueryService.class);
        knowledge = mock(KnowledgeSearchIndexDataService.class);
        runs = mock(AgentRunService.class);
        acceptedData = new AtomicReference<>();
        scheduledRequest = new AtomicReference<>();
        scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any())).thenAnswer(invocation -> {
            assertThat(acceptedData.get()).as("调度前运行必须已提交").isNotNull();
            scheduledRequest.set(invocation.getArgument(0));
            return true;
        });
        dispatchFailures = mock(PersistentAgentRunDispatchFailureHandler.class);
        dispatch = new TransactionAwareAgentRunDispatchCoordinator(scheduler, dispatchFailures);
        when(runs.findByOperatorAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(definitions.find("project_qa")).thenReturn(Optional.of(definition()));
        when(projects.resolveEnabledScope(any(), any())).thenReturn(project("main"));
        when(code.get(any(), any())).thenReturn(new ActiveCodeSnapshotView(
                "atlas", "main", CodeSnapshotAvailability.INDEXED, SNAPSHOT_ID, "abcdef1234567",
                NOW, 12L, null));
        when(knowledge.findActive()).thenReturn(Optional.of(new ActiveKnowledgeSearchGeneration(
                GENERATION_ID, "embedding", "a".repeat(64), 512, "chunk-v1", "fusion-v1", 2, 3, NOW)));
        when(runs.accept(any())).thenAnswer(invocation -> {
            AgentRunCreateData data = invocation.getArgument(0);
            acceptedData.set(data);
            return new AgentRunAcceptanceResult(snapshot(data, AgentRunStatus.ACCEPTED, null), true);
        });
        when(runs.findById(any())).thenAnswer(invocation -> {
            AgentRunCreateData data = acceptedData.get();
            return data == null ? Optional.empty() : Optional.of(snapshot(data, AgentRunStatus.ACCEPTED, null));
        });
    }

    /**
     * 业务目的：ADMIN/MEMBER 的合法问题必须先固定 main、快照、generation 和版本并落库，然后才把原问题交给专用调度器。
     */
    @Test
    void validMemberRequestPersistsFixedDefaultScopeBeforeScheduling() {
        AgentRunSnapshot result = service().start(command("member", "MEMBER", null, "  为什么要审核？  ", "key-1"));

        assertThat(result.status()).isEqualTo(AgentRunStatus.ACCEPTED);
        assertThat(acceptedData.get().scope().branch()).isEqualTo("main");
        assertThat(acceptedData.get().scope().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(acceptedData.get().scope().knowledgeGenerationId()).isEqualTo(GENERATION_ID);
        assertThat(acceptedData.get().versions().modelName()).isEqualTo("model-v1");
        assertThat(acceptedData.get().questionLength()).isEqualTo(7);
        assertThat(scheduledRequest.get().question()).isEqualTo("为什么要审核？");
        assertThat(acceptedData.get().toString()).doesNotContain("为什么要审核");
        System.out.printf("测试证据：场景=启动并固定范围，项目=%s，分支=%s，snapshot=%s，generation=%s，状态=%s%n",
                result.scope().projectIdentifier(), result.scope().branch(), result.scope().snapshotId(),
                result.scope().knowledgeGenerationId(), result.status());
    }

    /**
     * 业务目的：匿名/未知角色、空问题和超过 2000 个 Unicode 字符的问题必须在访问项目或模型前拒绝。
     */
    @Test
    void invalidIdentityAndUnicodeQuestionBoundsFailBeforeScopeLookup() {
        assertThatThrownBy(() -> service().start(command("", "MEMBER", null, "x", "key-a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().start(command("member", "GUEST", null, "x", "key-b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().start(command("member", "MEMBER", null, "  ", "key-c")))
                .isInstanceOf(IllegalArgumentException.class);
        String tooLong = "🚀".repeat(2001);
        assertThatThrownBy(() -> service().start(command("member", "MEMBER", null, tooLong, "key-d")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(projects, org.mockito.Mockito.never()).resolveEnabledScope(any(), any());
        System.out.println("测试证据：场景=启动输入边界，匿名/未知角色/空问题/2001个Unicode字符均被拒绝");
    }

    /**
     * 业务目的：Agent 关闭、模型 secret 缺失或 Skill 不可用时不得创建运行，并保留稳定错误码。
     */
    @Test
    void unavailableRuntimeModelOrSkillRejectsBeforeAcceptance() {
        configuration = configuration(false, false, "model-v1");
        assertCode(AgentErrorCode.AGENT_RUNTIME_UNAVAILABLE);
        configuration = configuration(true, false, "model-v1");
        assertCode(AgentErrorCode.AGENT_MODEL_UNAVAILABLE);
        configuration = configuration(true, true, "model-v1");
        when(definitions.find("project_qa")).thenReturn(Optional.empty());
        assertCode(AgentErrorCode.AGENT_SKILL_UNAVAILABLE);
        verify(runs, org.mockito.Mockito.never()).accept(any());
        System.out.println("测试证据：场景=Agent不可用，关闭/无模型/无Skill的稳定错误均已校验");
    }

    /**
     * 业务目的：同操作者同幂等键的相同请求必须返回原运行，即使配置已更新；不同问题必须冲突。
     */
    @Test
    void idempotentRetryReturnsPinnedRunAndDifferentQuestionConflicts() {
        AgentRunSnapshot original = service().start(command("member", "MEMBER", "main", "question", "same-key"));
        configuration = configuration(false, false, "model-v2");
        when(runs.findByOperatorAndIdempotencyKey("member", "same-key")).thenReturn(Optional.of(original));

        AgentRunSnapshot retried = service().start(command("member", "MEMBER", null, "question", "same-key"));
        assertThat(retried.runId()).isEqualTo(original.runId());
        assertThat(retried.versions().modelName()).isEqualTo("model-v1");
        assertThatThrownBy(() -> service().start(command("member", "MEMBER", null, "changed", "same-key")))
                .isInstanceOfSatisfying(AgentRequestException.class,
                        error -> assertThat(error.code()).isEqualTo(AgentErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT));
        configuration = configuration(true, true, "model-v2");
        AgentRunSnapshot newRun = service().start(command("member", "MEMBER", null, "new question", "new-key"));
        assertThat(newRun.versions().modelName()).isEqualTo("model-v2");
        System.out.printf("测试证据：场景=启动幂等，复用runId=%s，旧运行模型=%s，新运行模型=%s，变更问题=冲突%n",
                retried.runId(), retried.versions().modelName(), newRun.versions().modelName());
    }

    /**
     * 业务目的：显式分支和恰好 2000 个 Unicode 字符的问题必须被接受并固定，项目或分支无效则不得受理。
     */
    @Test
    void explicitBranchAndMaximumUnicodeQuestionArePinnedWhileInvalidScopeIsRejected() {
        when(projects.resolveEnabledScope("atlas", "feature/demo")).thenReturn(project("feature/demo"));
        String maximumQuestion = "问".repeat(1999) + "🚀";

        AgentRunSnapshot accepted = service().start(
                command("admin", "ADMIN", "feature/demo", maximumQuestion, "boundary-key"));

        assertThat(accepted.scope().branch()).isEqualTo("feature/demo");
        assertThat(accepted.questionLength()).isEqualTo(2000);
        when(projects.resolveEnabledScope("missing", "main")).thenThrow(new IllegalArgumentException("not found"));
        assertThatThrownBy(() -> service().start(
                new StartProjectQaRunCommand("missing-key", "admin", "ADMIN", "missing", null, "question")))
                .isInstanceOf(IllegalArgumentException.class);
        System.out.printf("测试证据：场景=显式范围与Unicode上界，分支=%s，问题字符数=%d，无效项目=拒绝%n",
                accepted.scope().branch(), accepted.questionLength());
    }

    /**
     * 业务目的：专用执行器队列满时，已接受运行必须终结为 AGENT_RUNTIME_BUSY 而不是静默丢失。
     */
    @Test
    void rejectedSchedulingPersistsRuntimeBusyTerminalFact() {
        scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any())).thenReturn(false);
        when(runs.finishWithError(any(), any(), any(Boolean.class), any(), any())).thenReturn(true);
        dispatch = new TransactionAwareAgentRunDispatchCoordinator(
                scheduler, new PersistentAgentRunDispatchFailureHandler(runs, Clock.fixed(NOW, java.time.ZoneOffset.UTC)));

        service().start(command("member", "MEMBER", null, "question", "busy-key"));

        verify(runs).finishWithError(any(), org.mockito.ArgumentMatchers.eq(AgentErrorCode.AGENT_RUNTIME_BUSY),
                org.mockito.ArgumentMatchers.eq(false), any(), any());
        System.out.println("测试证据：场景=Agent队列满，已接受运行终态错误=AGENT_RUNTIME_BUSY");
    }

    private StartProjectQaRunService service() {
        return new StartProjectQaRunService(configuration, definitions, projects, code, knowledge,
                runs, dispatch, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private StartProjectQaRunCommand command(String operator, String role, String branch, String question, String key) {
        return new StartProjectQaRunCommand(key, operator, role, "atlas", branch, question);
    }

    private void assertCode(AgentErrorCode expected) {
        assertThatThrownBy(() -> service().start(command(
                        "member", "MEMBER", null, "question", Long.toString(8000000000000000088L))))
                .isInstanceOfSatisfying(AgentRequestException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    private AgentProperties configuration(boolean enabled, boolean modelConfigured, String modelName) {
        return new AgentProperties(
                enabled,
                new AgentProperties.Model("openai-compatible", modelName,
                        modelConfigured ? "https://example.invalid" : "",
                        modelConfigured ? "test-key" : "",
                        Duration.ofSeconds(5), Duration.ofSeconds(60), 0),
                new AgentProperties.Policy("project-qa-v1"),
                new AgentProperties.Limits(
                        8, 8, Duration.ofSeconds(90), 10, 2000, 24000, 8000, 200, 0.1),
                new AgentProperties.Executor(1, 1, 1, Duration.ofSeconds(1)));
    }

    private AgentDefinition definition() {
        return new AgentDefinition("project_qa", "project-qa-v1", 8, "skill", "schema");
    }

    private ProjectScope project(String branch) {
        return new ProjectScope(PROJECT_ID, "atlas", "Atlas", true, BRANCH_ID, branch);
    }

    private AgentRunSnapshot snapshot(AgentRunCreateData data, AgentRunStatus status, AgentErrorCode code) {
        return new AgentRunSnapshot(data.runId() == null ? RUN_ID : data.runId(),
                data.operatorId(), data.idempotencyKey(), data.requestHash(),
                data.taskType(), status, null, null, null, code, data.scope(), data.versions(), data.questionLength(),
                0, 0, null, null, data.acceptedAt(), null, null, List.of());
    }
}
