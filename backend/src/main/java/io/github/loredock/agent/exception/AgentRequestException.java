package io.github.loredock.agent.exception;

import io.github.loredock.agent.model.enums.AgentErrorCode;

/** 启动 Agent 运行前产生的稳定、可脱敏请求错误。 */
public final class AgentRequestException extends RuntimeException {

    private final AgentErrorCode code;

    /** @param code 稳定错误码 */
    public AgentRequestException(AgentErrorCode code) {
        super(code.name());
        this.code = code;
    }

    /** @return 对外和日志使用的稳定错误码 */
    public AgentErrorCode code() {
        return code;
    }
}
