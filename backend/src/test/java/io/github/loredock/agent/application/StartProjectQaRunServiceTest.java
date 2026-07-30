package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.code.application.ActiveCodeSnapshotQueryUseCase;
import io.github.loredock.code.application.ActiveCodeSnapshotView;
import io.github.loredock.code.application.CodeSnapshotAvailability;
import io.github.loredock.knowledge.application.search.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.application.search.ActiveKnowledgeSearchGenerationReader;
import io.github.loredock.project.application.BranchView;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartProjectQaRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BRANCH_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SNAPSHOT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GENERATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private AgentRuntimeConfiguration configuration;
    private AgentSkillCatalog skills;
    private ProjectQueryUseCase projects;
    private ActiveCodeSnapshotQueryUseCase code;
    private ActiveKnowledgeSearchGenerationReader knowledge;
    private AgentRunRepository runs;
    private AgentEventRepository events;
    private AgentRunAcceptanceService acceptance;
    private AgentRunScheduler scheduler;
    private AtomicReference<AgentRunCreateData> acceptedData;
    private AtomicReference<AgentExecutionRequest> scheduledRequest;

    @BeforeEach
    void setUp() {
        configuration = configuration(true, true, "model-v1");
        skills = mock(AgentSkillCatalog.class);
        projects = mock(ProjectQueryUseCase.class);
        code = mock(ActiveCodeSnapshotQueryUseCase.class);
        knowledge = mock(ActiveKnowledgeSearchGenerationReader.class);
        runs = mock(AgentRunRepository.class);
        events = mock(AgentEventRepository.class);
        acceptance = mock(AgentRunAcceptanceService.class);
        acceptedData = new AtomicReference<>();
        scheduledRequest = new AtomicReference<>();
        scheduler = request -> {
            assertThat(acceptedData.get()).as("调度前运行必须已提交").isNotNull();
            scheduledRequest.set(request);
            return true;
        };
        when(runs.findByOperatorAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(skills.findEnabled("project_qa")).thenReturn(Optional.of(skill()));
        when(projects.getEnabledProject(any(), any())).thenReturn(project("main"));
        when(code.get(any(), any())).thenReturn(new ActiveCodeSnapshotView(
                "atlas", "main", CodeSnapshotAvailability.INDEXED, SNAPSHOT_ID, "abcdef1234567",
                NOW, 12L, null));
        when(knowledge.findActive()).thenReturn(Optional.of(new ActiveKnowledgeSearchGeneration(
                GENERATION_ID, "embedding", "a".repeat(64), 512, "chunk-v1", "fusion-v1", 2, 3, NOW)));
        when(acceptance.accept(any())).thenAnswer(invocation -> {
            AgentRunCreateData data = invocation.getArgument(0);
            acceptedData.set(data);
            return snapshot(data, AgentRunStatus.ACCEPTED, null);
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
        verify(projects, org.mockito.Mockito.never()).getEnabledProject(any(), any());
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
        when(skills.findEnabled("project_qa")).thenReturn(Optional.empty());
        assertCode(AgentErrorCode.AGENT_SKILL_UNAVAILABLE);
        verify(acceptance, org.mockito.Mockito.never()).accept(any());
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
        when(projects.getEnabledProject("atlas", "feature/demo")).thenReturn(project("feature/demo"));
        String maximumQuestion = "问".repeat(1999) + "🚀";

        AgentRunSnapshot accepted = service().start(
                command("admin", "ADMIN", "feature/demo", maximumQuestion, "boundary-key"));

        assertThat(accepted.scope().branch()).isEqualTo("feature/demo");
        assertThat(accepted.questionLength()).isEqualTo(2000);
        when(projects.getEnabledProject("missing", "main")).thenThrow(new IllegalArgumentException("not found"));
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
        scheduler = request -> false;
        when(runs.finishWithError(any(), any(), any(Boolean.class), any(), any())).thenReturn(true);

        service().start(command("member", "MEMBER", null, "question", "busy-key"));

        verify(runs).finishWithError(any(), org.mockito.ArgumentMatchers.eq(AgentErrorCode.AGENT_RUNTIME_BUSY),
                org.mockito.ArgumentMatchers.eq(false), any(), any());
        verify(events).append(any(), org.mockito.ArgumentMatchers.eq(io.github.loredock.agent.domain.AgentEventType.RUN_FAILED),
                org.mockito.ArgumentMatchers.eq(AgentErrorCode.AGENT_RUNTIME_BUSY.name()), any());
        System.out.println("测试证据：场景=Agent队列满，已接受运行终态错误=AGENT_RUNTIME_BUSY");
    }

    private StartProjectQaRunService service() {
        return new StartProjectQaRunService(configuration, skills, projects, code, knowledge,
                runs, events, acceptance, scheduler, () -> NOW);
    }

    private StartProjectQaRunCommand command(String operator, String role, String branch, String question, String key) {
        return new StartProjectQaRunCommand(key, operator, role, "atlas", branch, question);
    }

    private void assertCode(AgentErrorCode expected) {
        assertThatThrownBy(() -> service().start(command("member", "MEMBER", null, "question", UUID.randomUUID().toString())))
                .isInstanceOfSatisfying(AgentRequestException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    private AgentRuntimeConfiguration configuration(boolean enabled, boolean modelConfigured, String modelName) {
        return new AgentRuntimeConfiguration() {
            public boolean enabled() { return enabled; }
            public boolean modelConfigured() { return modelConfigured; }
            public String modelProvider() { return "openai-compatible"; }
            public String modelName() { return modelName; }
            public String outputSchemaVersion() { return "project-qa-v1"; }
            public String toolPolicyVersion() { return "project-qa-readonly-v1"; }
            public String limitPolicyVersion() { return "project-qa-policy-v1"; }
            public AgentRuntimeLimits runtimeLimits() {
                return new AgentRuntimeLimits(8, 8, Duration.ofSeconds(90), 10, 2000, 24000, 8000, 200);
            }
            public Duration totalTimeout() { return Duration.ofSeconds(90); }
        };
    }

    private AgentSkillSnapshot skill() {
        return new AgentSkillSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "a".repeat(64),
                "opaque", "project-qa-v1", "skill", "schema");
    }

    private ProjectDetailView project(String branch) {
        BranchView view = new BranchView(BRANCH_ID, branch, NOW, NOW, "SYSTEM", "SYSTEM");
        return new ProjectDetailView(PROJECT_ID, "atlas", "Atlas", "", "Java", "main", branch, List.of(view));
    }

    private AgentRunSnapshot snapshot(AgentRunCreateData data, AgentRunStatus status, AgentErrorCode code) {
        return new AgentRunSnapshot(data.runId(), data.operatorId(), data.idempotencyKey(), data.requestHash(),
                data.taskType(), status, null, null, null, code, data.scope(), data.versions(), data.questionLength(),
                0, 0, null, null, data.acceptedAt(), null, null, List.of());
    }
}
