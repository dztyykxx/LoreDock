package io.github.loredock.agent.exception;

import io.github.loredock.agent.model.enums.AgentErrorCode;

/** 只读工具在白名单、固定范围或版本校验失败时使用的稳定异常。 */
public class AgentToolException extends RuntimeException {

    private final AgentErrorCode code;

    /** @param code 不包含底层路径、正文或连接信息的稳定错误码 */
    public AgentToolException(AgentErrorCode code) {
        super(code.name());
        this.code = code;
    }

    /** @return 可写入运行终态的稳定错误码 */
    public AgentErrorCode code() {
        return code;
    }
}
