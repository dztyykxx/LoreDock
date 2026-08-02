package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentRequestException;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.api.AgentRunNotFoundException;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.model.command.AgentRunCreateData;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentRunAcceptanceResult;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.agent.scheduler.BoundedAgentRunScheduler;
import io.github.loredock.knowledge.api.KnowledgeSearchService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Agent 公开契约实现：启动时先固定范围并短事务落库，查询时复核操作者与项目访问，
 * 事件只暴露已提交的安全载荷；项目问答 Skill 直接来自框架 classpath Registry。
 */
@Service
@Slf4j
public class AgentServiceImpl implements AgentService {

    private static final String TASK_TYPE = "project_qa";
    private static final String SKILL_NAME = "project-qa";
    private static final int MAX_EVENT_PAGE_SIZE = 200;
    private static final int MAX_HISTORY_MESSAGES = 16;
    private static final int MAX_HISTORY_CODE_POINTS = 8000;
    private static final ObjectMapper EVENT_JSON = new ObjectMapper().findAndRegisterModules();
    private final AgentProperties configuration;
    private final ClasspathSkillRegistry skills;
    private final String outputSchema;
    private final ProjectService projects;
    private final KnowledgeSearchService knowledge;
    private final AgentRunService runs;
    private final AgentEventService events;
    private final BoundedAgentRunScheduler scheduler;
    private final ProjectQaRunTaskExecutor taskExecutor;
    private final PersistentAgentRunDispatchFailureHandler dispatchFailures;
    private final Clock timeProvider;

    /**
     * @param configuration Agent 受控配置
     * @param skills 框架 classpath Skill Registry
     * @param outputSchema 固定输出 JSON Schema
     * @param projects 启用项目与分支查询
     * @param knowledge 知识检索与活动索引版本契约
     * @param runs 运行仓储
     * @param events 已提交公开事件仓储与进程内通知
     * @param scheduler Agent 专用有界调度器
     * @param taskExecutor project_qa 具体运行执行器
     * @param dispatchFailures 提交后调度失败的独立事务终态处理
     * @param timeProvider UTC 时间源
     */
    public AgentServiceImpl(
            AgentProperties configuration,
            ClasspathSkillRegistry skills,
            @Qualifier("projectQaOutputSchema") String outputSchema,
            ProjectService projects,
            KnowledgeSearchService knowledge,
            AgentRunService runs,
            AgentEventService events,
            BoundedAgentRunScheduler scheduler,
            ProjectQaRunTaskExecutor taskExecutor,
            PersistentAgentRunDispatchFailureHandler dispatchFailures,
            Clock timeProvider
    ) {
        this.configuration = configuration;
        this.skills = skills;
        this.outputSchema = outputSchema;
        this.projects = projects;
        this.knowledge = knowledge;
        this.runs = runs;
        this.events = events;
        this.scheduler = scheduler;
        this.taskExecutor = taskExecutor;
        this.dispatchFailures = dispatchFailures;
        this.timeProvider = timeProvider;
    }

    @Override
    public AgentRun start(StartRequest request) {
        Objects.requireNonNull(request, "agent start request is required");
        return toApiRun(startSnapshot(request));
    }

    AgentRunSnapshot startSnapshot(StartRequest command) {
        NormalizedInput input = normalize(command);
        String requestHash = hash(TASK_TYPE + "\n" + input.projectIdentifier() + "\n" + input.branch()
                + "\n" + input.question() + "\n" + historyHashInput(input.conversationHistory()));
        var existing = runs.findByOperatorAndIdempotencyKey(input.operatorId(), input.idempotencyKey());
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().requestHash())) {
                throw new AgentRequestException(AgentRun.ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT);
            }
            return existing.get();
        }
        requireAvailable();
        String instructions = readProjectQaSkill();
        ProjectScope project = projects.resolveEnabledScope(input.projectIdentifier(), input.branch());
        Long generationId = knowledge.findActiveIndexVersionId().orElse(null);
        AgentScopeSnapshot scope = new AgentScopeSnapshot(
                project.projectId(), project.projectIdentifier(), project.branchId(), project.branchName(),
                null, null,
                generationId, List.of("GLOBAL", "PROJECT", "BRANCH"));
        AgentVersionSnapshot versions = new AgentVersionSnapshot(
                SKILL_NAME, configuration.modelName(), configuration.outputSchemaVersion());
        Instant acceptedAt = timeProvider.instant();
        AgentRunCreateData data = new AgentRunCreateData(
                null, input.operatorId(), input.idempotencyKey(), requestHash, TASK_TYPE,
                hash(input.question()), input.question().codePointCount(0, input.question().length()),
                scope, versions, acceptedAt);
        AgentRunAcceptanceResult acceptanceResult = runs.accept(data);
        AgentRunSnapshot accepted = acceptanceResult.snapshot();
        if (!acceptanceResult.newlyAccepted()) {
            if (!requestHash.equals(accepted.requestHash())) {
                throw new AgentRequestException(AgentRun.ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT);
            }
            return accepted;
        }
        Long runId = accepted.runId();
        AgentExecutionRequest request = new AgentExecutionRequest(
                runId, input.question(), instructions, outputSchema, scope, versions,
                configuration.runtimeLimits(), acceptedAt.plus(configuration.totalTimeout()),
                input.conversationHistory());
        dispatchAfterCommit(request);
        AgentRunSnapshot result = runs.findById(runId).orElse(accepted);
        log.info("agent_run start completed traceId={} runId={} project={} branch={} "
                        + "knowledgeGenerationAvailable={} status={}",
                traceId(runId), runId, scope.projectIdentifier(), scope.branch(),
                scope.knowledgeGenerationId() != null, result.status());
        return result;
    }

    private String readProjectQaSkill() {
        if (skills.get(SKILL_NAME).isEmpty()) {
            throw new AgentRequestException(AgentRun.ErrorCode.AGENT_SKILL_UNAVAILABLE);
        }
        try {
            return skills.readSkillContent(SKILL_NAME);
        } catch (IOException | RuntimeException exception) {
            throw new AgentRequestException(AgentRun.ErrorCode.AGENT_SKILL_UNAVAILABLE);
        }
    }

    @Override
    public AgentRun get(Long runId, String operatorId) {
        return toApiRun(authorized(runId, operatorId));
    }

    @Override
    public List<AgentEvent> listEvents(Long runId, String operatorId, long afterSequence, int limit) {
        authorized(runId, operatorId);
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence invalid");
        }
        int bounded = Math.min(Math.max(limit, 1), MAX_EVENT_PAGE_SIZE);
        return events.findAfter(runId, afterSequence, bounded).stream().map(this::toApiEvent).toList();
    }

    @Override
    public long lastEventSequence(Long runId, String operatorId) {
        authorized(runId, operatorId);
        return events.lastSequence(runId);
    }

    @Override
    public Subscription subscribe(Long runId) {
        AgentEventService.EventSubscription subscription = events.subscribe(runId);
        return new Subscription() {
            @Override
            public AgentEvent poll(Duration timeout) throws InterruptedException {
                var event = subscription.poll(timeout);
                return event == null ? null : toApiEvent(event);
            }

            @Override
            public void close() {
                subscription.close();
            }
        };
    }

    private AgentRunSnapshot authorized(Long runId, String operatorId) {
        if (runId == null || operatorId == null || operatorId.isBlank()) {
            throw new AgentRunNotFoundException();
        }
        AgentRunSnapshot snapshot = runs.findById(runId).orElseThrow(AgentRunNotFoundException::new);
        if (!snapshot.operatorId().equals(operatorId)) {
            throw new AgentRunNotFoundException();
        }
        try {
            projects.resolveEnabledScope(snapshot.scope().projectIdentifier(), snapshot.scope().branch());
        } catch (RuntimeException exception) {
            throw new AgentRunNotFoundException();
        }
        return snapshot;
    }

    private AgentRun toApiRun(AgentRunSnapshot snapshot) {
        AgentScopeSnapshot scope = snapshot.scope();
        return new AgentRun(
                snapshot.runId(), AgentRun.Status.valueOf(snapshot.status().name()),
                enumValue(AgentRun.ResultType.class, snapshot.resultType()),
                enumValue(AgentRun.AnswerBasis.class, snapshot.answerBasis()), snapshot.resultText(),
                enumValue(AgentRun.RefusalReason.class, snapshot.refusalReason()),
                enumValue(AgentRun.ErrorCode.class, snapshot.errorCode()),
                new AgentRun.Scope(scope.projectId(), scope.projectIdentifier(), scope.branchId(), scope.branch(),
                        scope.snapshotId(), scope.commit(), scope.knowledgeGenerationId()),
                snapshot.stepCount(), snapshot.modelCallCount(), snapshot.acceptedAt(), snapshot.startedAt(),
                snapshot.finishedAt(), snapshot.citations().stream().map(citation -> new AgentRun.Citation(
                        citation.evidenceId(), AgentRun.EvidenceSourceType.valueOf(citation.sourceType().name()),
                        citation.documentId(), citation.snapshotId(), citation.projectIdentifier(), citation.branch(),
                        citation.commit(), citation.repositoryPath(), citation.title(), citation.sourceUpdatedAt(),
                        citation.order(), new AgentRun.SourceMetadata(
                                citation.sourceMetadata().schemaVersion(), citation.sourceMetadata().scopeType(),
                                citation.sourceMetadata().knowledgeSourceType(), citation.sourceMetadata().wikiUrl(),
                                citation.sourceMetadata().originalFilename()))).toList());
    }

    private AgentEvent toApiEvent(io.github.loredock.agent.model.snapshot.AgentEventSnapshot event) {
        AgentEvent.Type type = AgentEvent.Type.valueOf(event.type().name());
        if (!event.payload().startsWith("{")) {
            return new AgentEvent(event.eventId(), event.runId(), event.sequence(),
                    type, event.payload(), event.createdAt());
        }
        try {
            return new AgentEvent(event.eventId(), event.runId(), event.sequence(), type,
                    AgentEvent.SubjectType.valueOf(event.subjectType()),
                    EVENT_JSON.readValue(event.payload(), AgentEvent.Payload.class), event.createdAt());
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("agent typed event payload invalid", exception);
        }
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, Enum<?> value) {
        return value == null ? null : Enum.valueOf(type, value.name());
    }

    private void dispatchAfterCommit(AgentExecutionRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // Web 问答将运行和用户消息放在同一外层事务；只有提交成功后才允许工作线程读取。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(request);
                }
            });
            return;
        }
        dispatch(request);
    }

    private void dispatch(AgentExecutionRequest request) {
        try {
            if (!scheduler.schedule(request.runId(), () -> taskExecutor.execute(request))) {
                finishDispatchFailure(request.runId(), AgentErrorCode.AGENT_RUNTIME_BUSY);
            }
        } catch (RuntimeException exception) {
            log.error("agent_run dispatch failed runId={} errorCode={}",
                    request.runId(), AgentErrorCode.AGENT_INTERNAL_ERROR, exception);
            finishDispatchFailure(request.runId(), AgentErrorCode.AGENT_INTERNAL_ERROR);
        }
    }

    private void finishDispatchFailure(Long runId, AgentErrorCode errorCode) {
        try {
            dispatchFailures.finish(runId, errorCode);
        } catch (RuntimeException exception) {
            // 原事务已经提交；失败终态持久化异常只记录，启动恢复会终结遗留活动运行。
            log.error("agent_run dispatch failure persistence failed runId={} errorCode={}",
                    runId, errorCode, exception);
        }
    }

    private String traceId(Long runId) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? runId.toString() : current;
    }

    private void requireAvailable() {
        if (!configuration.enabled()) {
            throw new AgentRequestException(AgentRun.ErrorCode.AGENT_RUNTIME_UNAVAILABLE);
        }
        if (!configuration.modelConfigured()) {
            throw new AgentRequestException(AgentRun.ErrorCode.AGENT_MODEL_UNAVAILABLE);
        }
    }

    private NormalizedInput normalize(StartRequest command) {
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
        List<ConversationMessage> history = normalizeHistory(command.conversationHistory());
        return new NormalizedInput(key, operatorId, project, branch, question, history);
    }

    private List<ConversationMessage> normalizeHistory(List<ConversationMessage> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (source.size() > MAX_HISTORY_MESSAGES) {
            throw new IllegalArgumentException("conversation history too large");
        }
        int characters = 0;
        java.util.ArrayList<ConversationMessage> values = new java.util.ArrayList<>(source.size());
        for (ConversationMessage message : source) {
            Objects.requireNonNull(message, "conversation history message");
            String role = required(message.role(), 16, "conversation history role invalid")
                    .toUpperCase(Locale.ROOT);
            if (!role.equals("USER") && !role.equals("ASSISTANT")) {
                throw new IllegalArgumentException("conversation history role invalid");
            }
            String content = required(message.content(), 4000, "conversation history content invalid");
            characters += content.codePointCount(0, content.length());
            if (characters > MAX_HISTORY_CODE_POINTS) {
                throw new IllegalArgumentException("conversation history too large");
            }
            values.add(new ConversationMessage(role, content,
                    Objects.requireNonNull(message.occurredAt(), "conversation history time")));
        }
        return List.copyOf(values);
    }

    private String historyHashInput(List<ConversationMessage> history) {
        StringBuilder value = new StringBuilder();
        history.forEach(message -> value.append(message.role()).append('\n')
                .append(message.occurredAt()).append('\n').append(message.content()).append('\n'));
        return value.toString();
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
            String question,
            List<ConversationMessage> conversationHistory
    ) {
    }
}
