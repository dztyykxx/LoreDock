package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;

import java.util.UUID;

/** 以独立短事务保存提交后调度失败，避免已接受运行被静默遗留。 */
public interface AgentRunDispatchFailureHandler {

    /**
     * @param runId 已提交运行标识
     * @param errorCode 脱敏后的稳定调度错误码
     */
    void finish(UUID runId, AgentErrorCode errorCode);
}
