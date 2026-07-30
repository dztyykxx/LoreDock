package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.TrustedProjectQaResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Agent 运行事实的短事务持久化端口。 */
public interface AgentRunRepository {

    /** @return 同一操作者和幂等键的既有运行 */
    Optional<AgentRunSnapshot> findByOperatorAndIdempotencyKey(String operatorId, String idempotencyKey);

    /** @return 按标识查询的运行；不存在时为空 */
    Optional<AgentRunSnapshot> findById(UUID runId);

    /** 插入 ACCEPTED 运行；唯一约束负责兜底并发幂等。 */
    void insert(AgentRunCreateData data);

    /**
     * 以 PostgreSQL 冲突忽略原子受理运行，避免外层 Web 事务因并发唯一键竞争进入失败状态。
     * @return 本事务实际插入运行时为 true
     */
    boolean insertIfAbsent(AgentRunCreateData data);

    /** @return 仅从 ACCEPTED 比较更新到 RUNNING 成功时为 true */
    boolean markRunning(UUID runId, Instant startedAt);

    /** @return 仅从 RUNNING 比较更新到可信 COMPLETED 成功时为 true */
    boolean complete(UUID runId, TrustedProjectQaResult result, AgentExecutionUsage usage, Instant finishedAt);

    /** @return 仅从非终态比较更新到 FAILED 或 TERMINATED 成功时为 true */
    boolean finishWithError(UUID runId, AgentErrorCode code, boolean terminated, AgentExecutionUsage usage, Instant finishedAt);

    /** @return 进程重启时发现的 ACCEPTED 与 RUNNING 运行 */
    List<AgentRunSnapshot> findNonTerminalRuns();
}
