package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;

import java.time.Instant;
import java.util.UUID;

/** 工具调用有限摘要的短事务持久化端口，不接收查询正文、片段正文或服务器路径。 */
public interface AgentToolCallRepository {

    /** @return 已提交的 RUNNING 工具调用及运行内序号 */
    AgentToolCallStart start(UUID runId, String toolName, String safeArgumentSummary, Instant startedAt);

    /** 把指定调用单调更新为成功并保存有限计数。 */
    void succeed(UUID callId, int resultCount, int evidenceCount, Instant finishedAt);

    /** 把指定调用单调更新为失败并保存稳定错误码。 */
    void fail(UUID callId, AgentErrorCode code, Instant finishedAt);
}
