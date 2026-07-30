package io.github.loredock.agent.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单 Agent 运行状态机。对象只表达单调状态规则；跨进程并发仍由持久化比较更新保证。
 */
public final class AgentRun {

    private final UUID id;
    private final String operatorId;
    private final String taskType;
    private final String requestHash;
    private final String questionHash;
    private final int questionLength;
    private final AgentScopeSnapshot scope;
    private final AgentVersionSnapshot versions;
    private final Instant acceptedAt;
    private AgentRunStatus status;
    private AgentResultType resultType;
    private String resultText;
    private AgentRefusalReason refusalReason;
    private AgentErrorCode errorCode;
    private Instant startedAt;
    private Instant finishedAt;
    private int stepCount;
    private int modelCallCount;
    private Long inputTokens;
    private Long outputTokens;

    private AgentRun(
            UUID id,
            String operatorId,
            String taskType,
            String requestHash,
            String questionHash,
            int questionLength,
            AgentScopeSnapshot scope,
            AgentVersionSnapshot versions,
            Instant acceptedAt
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.operatorId = required(operatorId, "operatorId");
        this.taskType = required(taskType, "taskType");
        this.requestHash = hash(requestHash, "requestHash");
        this.questionHash = hash(questionHash, "questionHash");
        if (questionLength < 1 || questionLength > 2000) {
            throw new IllegalArgumentException("questionLength must be between 1 and 2000");
        }
        this.questionLength = questionLength;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        this.status = AgentRunStatus.ACCEPTED;
    }

    /**
     * 创建已接受但尚未调度的运行；此时即固定版本和业务范围。
     */
    public static AgentRun accepted(
            UUID id,
            String operatorId,
            String taskType,
            String requestHash,
            String questionHash,
            int questionLength,
            AgentScopeSnapshot scope,
            AgentVersionSnapshot versions,
            Instant acceptedAt
    ) {
        return new AgentRun(id, operatorId, taskType, requestHash, questionHash, questionLength,
                scope, versions, acceptedAt);
    }

    /** @return 成功进入 RUNNING 时为 true；重复或终态调用为 false */
    public boolean start(Instant now) {
        if (status != AgentRunStatus.ACCEPTED) {
            return false;
        }
        status = AgentRunStatus.RUNNING;
        startedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    /**
     * 完成可信回答或拒答。终态返回 false，用于明确丢弃超时后的迟到结果。
     */
    public boolean complete(
            AgentResultType type,
            String text,
            AgentRefusalReason reason,
            Instant now
    ) {
        if (status != AgentRunStatus.RUNNING) {
            return false;
        }
        AgentResultType requiredType = Objects.requireNonNull(type, "type");
        if (requiredType == AgentResultType.REFUSAL && reason == null) {
            throw new IllegalArgumentException("refusal reason required");
        }
        status = AgentRunStatus.COMPLETED;
        resultType = requiredType;
        resultText = required(text, "text");
        refusalReason = reason;
        finishedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    /** @return 成功进入 FAILED 时为 true；终态或未开始运行时为 false */
    public boolean fail(AgentErrorCode code, Instant now) {
        return finishFailure(AgentRunStatus.FAILED, code, now);
    }

    /** @return 成功进入 TERMINATED 时为 true；终态时为 false */
    public boolean terminate(AgentErrorCode code, Instant now) {
        return finishFailure(AgentRunStatus.TERMINATED, code, now);
    }

    /**
     * 覆盖本次执行实际计数。Token 为 null 代表模型未提供，不得换算成零。
     */
    public void updateUsage(int steps, int modelCalls, Long inputTokenCount, Long outputTokenCount) {
        if (steps < 0 || modelCalls < 0 || negative(inputTokenCount) || negative(outputTokenCount)) {
            throw new IllegalArgumentException("usage must not be negative");
        }
        stepCount = steps;
        modelCallCount = modelCalls;
        inputTokens = inputTokenCount;
        outputTokens = outputTokenCount;
    }

    private boolean finishFailure(AgentRunStatus target, AgentErrorCode code, Instant now) {
        if (status.terminal()) {
            return false;
        }
        status = target;
        errorCode = Objects.requireNonNull(code, "code");
        finishedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    private static boolean negative(Long value) {
        return value != null && value < 0;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value;
    }

    private static String hash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase sha256");
        }
        return value;
    }

    public UUID id() { return id; }
    public String operatorId() { return operatorId; }
    public String taskType() { return taskType; }
    public String requestHash() { return requestHash; }
    public String questionHash() { return questionHash; }
    public int questionLength() { return questionLength; }
    public AgentScopeSnapshot scope() { return scope; }
    public AgentVersionSnapshot versions() { return versions; }
    public Instant acceptedAt() { return acceptedAt; }
    public AgentRunStatus status() { return status; }
    public AgentResultType resultType() { return resultType; }
    public String resultText() { return resultText; }
    public AgentRefusalReason refusalReason() { return refusalReason; }
    public AgentErrorCode errorCode() { return errorCode; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public int stepCount() { return stepCount; }
    public int modelCallCount() { return modelCallCount; }
    public Long inputTokens() { return inputTokens; }
    public Long outputTokens() { return outputTokens; }
}
