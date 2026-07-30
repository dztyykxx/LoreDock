package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.code.application.ActiveCodeSnapshotQueryUseCase;
import io.github.loredock.code.application.CodeSnapshotAvailability;
import io.github.loredock.knowledge.application.search.ActiveKnowledgeSearchGenerationReader;
import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.project.application.BranchView;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * `project_qa` 运行启动服务：先完成输入和范围固定，再短事务落库，最后在提交后调度。
 */
@Service
@Slf4j
public class StartProjectQaRunService implements StartProjectQaRunUseCase {

    private static final String TASK_TYPE = "project_qa";
    private final AgentRuntimeConfiguration configuration;
    private final AgentSkillCatalog skills;
    private final ProjectQueryUseCase projects;
    private final ActiveCodeSnapshotQueryUseCase codeSnapshots;
    private final ActiveKnowledgeSearchGenerationReader knowledgeGenerations;
    private final AgentRunRepository runs;
    private final AgentRunAcceptanceService acceptance;
    private final AgentRunDispatchCoordinator dispatch;
    private final TimeProvider timeProvider;

    /**
     * @param configuration Agent 受控配置
     * @param skills 已发布 Skill 目录
     * @param projects 启用项目与分支查询
     * @param codeSnapshots 活动代码快照查询
     * @param knowledgeGenerations 活动知识 generation 查询
     * @param runs 运行仓储
     * @param acceptance 接受短事务
     * @param dispatch 最外层事务提交后调度器
     * @param timeProvider UTC 时间源
     */
    public StartProjectQaRunService(
            AgentRuntimeConfiguration configuration,
            AgentSkillCatalog skills,
            ProjectQueryUseCase projects,
            ActiveCodeSnapshotQueryUseCase codeSnapshots,
            ActiveKnowledgeSearchGenerationReader knowledgeGenerations,
            AgentRunRepository runs,
            AgentRunAcceptanceService acceptance,
            AgentRunDispatchCoordinator dispatch,
            TimeProvider timeProvider
    ) {
        this.configuration = configuration;
        this.skills = skills;
        this.projects = projects;
        this.codeSnapshots = codeSnapshots;
        this.knowledgeGenerations = knowledgeGenerations;
        this.runs = runs;
        this.acceptance = acceptance;
        this.dispatch = dispatch;
        this.timeProvider = timeProvider;
    }

    @Override
    public AgentRunSnapshot start(StartProjectQaRunCommand command) {
        NormalizedInput input = normalize(command);
        String requestHash = hash(TASK_TYPE + "\n" + input.projectIdentifier() + "\n" + input.branch()
                + "\n" + input.question());
        var existing = runs.findByOperatorAndIdempotencyKey(input.operatorId(), input.idempotencyKey());
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().requestHash())) {
                throw new AgentRequestException(AgentErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT);
            }
            return existing.get();
        }
        requireAvailable();
        AgentSkillSnapshot skill = skills.findEnabled(TASK_TYPE)
                .orElseThrow(() -> new AgentRequestException(AgentErrorCode.AGENT_SKILL_UNAVAILABLE));
        if (!configuration.outputSchemaVersion().equals(skill.outputSchemaVersion())) {
            throw new AgentRequestException(AgentErrorCode.AGENT_SKILL_UNAVAILABLE);
        }
        ProjectDetailView project = projects.getEnabledProject(input.projectIdentifier(), input.branch());
        BranchView branch = project.branches().stream()
                .filter(value -> value.name().equals(project.selectedBranch()))
                .findFirst().orElseThrow(() -> new IllegalStateException("项目分支解析结果不完整"));
        var code = codeSnapshots.get(project.identifier(), project.selectedBranch());
        UUID generationId = knowledgeGenerations.findActive().map(value -> value.generationId()).orElse(null);
        AgentScopeSnapshot scope = new AgentScopeSnapshot(
                project.id(), project.identifier(), branch.id(), project.selectedBranch(),
                code.status() == CodeSnapshotAvailability.INDEXED ? code.snapshotId() : null,
                code.status() == CodeSnapshotAvailability.INDEXED ? code.commit() : null,
                generationId, List.of("GLOBAL", "PROJECT", "BRANCH"));
        AgentVersionSnapshot versions = new AgentVersionSnapshot(
                skill.id(), skill.name(), skill.version(), skill.contentHash(), configuration.modelProvider(),
                configuration.modelName(), configuration.outputSchemaVersion(),
                configuration.toolPolicyVersion(), configuration.limitPolicyVersion());
        Instant acceptedAt = timeProvider.now();
        UUID runId = UUID.randomUUID();
        AgentRunCreateData data = new AgentRunCreateData(
                runId, input.operatorId(), input.idempotencyKey(), requestHash, TASK_TYPE,
                hash(input.question()), input.question().codePointCount(0, input.question().length()),
                scope, versions, acceptedAt);
        AgentRunSnapshot accepted;
        try {
            accepted = acceptance.accept(data);
        } catch (DataIntegrityViolationException exception) {
            AgentRunSnapshot raced = runs.findByOperatorAndIdempotencyKey(input.operatorId(), input.idempotencyKey())
                    .orElseThrow(() -> exception);
            if (!requestHash.equals(raced.requestHash())) {
                throw new AgentRequestException(AgentErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT);
            }
            return raced;
        }
        AgentExecutionRequest request = new AgentExecutionRequest(
                runId, input.question(), skill.markdown(), skill.outputSchema(), scope, versions,
                configuration.runtimeLimits(), acceptedAt.plus(configuration.totalTimeout()));
        dispatch.dispatchAfterCommit(request);
        AgentRunSnapshot result = runs.findById(runId).orElse(accepted);
        log.info("agent_run start completed traceId={} runId={} project={} branch={} snapshotAvailable={} "
                        + "knowledgeGenerationAvailable={} status={}",
                traceId(runId), runId, scope.projectIdentifier(), scope.branch(), scope.hasCodeSnapshot(),
                scope.knowledgeGenerationId() != null, result.status());
        return result;
    }

    private String traceId(UUID runId) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? runId.toString() : current;
    }

    private void requireAvailable() {
        if (!configuration.enabled()) {
            throw new AgentRequestException(AgentErrorCode.AGENT_RUNTIME_UNAVAILABLE);
        }
        if (!configuration.modelConfigured()) {
            throw new AgentRequestException(AgentErrorCode.AGENT_MODEL_UNAVAILABLE);
        }
    }

    private NormalizedInput normalize(StartProjectQaRunCommand command) {
        Objects.requireNonNull(command, "command");
        String operatorId = required(command.operatorId(), 128, "operator invalid");
        String role = required(command.operatorRole(), 16, "role invalid").toUpperCase(Locale.ROOT);
        if (!role.equals("ADMIN") && !role.equals("MEMBER")) {
            throw new IllegalArgumentException("operator role invalid");
        }
        String key = required(command.idempotencyKey(), 128, "idempotency key invalid");
        String project = required(command.projectIdentifier(), 64, "project identifier invalid");
        String branch = command.branch() == null || command.branch().isBlank() ? "main" : command.branch().strip();
        if (branch.codePointCount(0, branch.length()) > 255) {
            throw new IllegalArgumentException("branch invalid");
        }
        String question = Objects.requireNonNull(command.question(), "question").strip();
        int questionLength = question.codePointCount(0, question.length());
        if (questionLength < 1 || questionLength > 2000) {
            throw new IllegalArgumentException("question length invalid");
        }
        return new NormalizedInput(key, operatorId, project, branch, question);
    }

    private String required(String value, int maximumCodePoints, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        String normalized = value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > maximumCodePoints) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }

    private record NormalizedInput(
            String idempotencyKey,
            String operatorId,
            String projectIdentifier,
            String branch,
            String question
    ) {
    }
}
