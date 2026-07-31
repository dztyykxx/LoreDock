package io.github.loredock.agent.api;

import java.time.Instant;

/**
 * 不包含隐藏思维链的已提交公开事件。
 *
 * @param eventId 事件标识
 * @param runId 运行标识
 * @param sequence 运行内连续序号
 * @param type 公开事件类型
 * @param payload 只含安全状态、数量或错误摘要的载荷
 * @param createdAt 创建时间
 */
public record AgentEvent(
        Long eventId,
        Long runId,
        long sequence,
        Type type,
        String payload,
        Instant createdAt
) {
    /** 公开运行事件类型。 */
    public enum Type {
        RUN_ACCEPTED, RUN_STARTED, MODEL_STARTED, SOURCE_FOUND, RUN_COMPLETED, RUN_FAILED, RUN_TERMINATED
    }
}
