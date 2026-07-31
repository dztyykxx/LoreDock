package io.github.loredock.agent.exception;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.result.AgentExecutionUsage;

/** 模型执行在计数、截止时间或响应解析边界失败时使用的稳定异常。 */
public class AgentExecutionException extends RuntimeException {

    private final AgentErrorCode code;
    private final AgentExecutionUsage usage;

    /** @param code 可直接映射到运行终态且不包含模型原文的错误码 */
    public AgentExecutionException(AgentErrorCode code) {
        this(code, AgentExecutionUsage.none());
    }

    /** @param code 稳定错误码 @param usage 失败前已经真实发生的步骤、工具和 Token 计数 */
    public AgentExecutionException(AgentErrorCode code, AgentExecutionUsage usage) {
        super(code.name());
        this.code = code;
        this.usage = usage;
    }

    /** @return 运行终态稳定错误码 */
    public AgentErrorCode code() {
        return code;
    }

    /** @return 失败前实际用量；无法确认的 Token 保持 null */
    public AgentExecutionUsage usage() {
        return usage;
    }
}
