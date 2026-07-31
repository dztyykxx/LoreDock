package io.github.loredock.agent.api;

/** Agent 受理请求被稳定业务规则拒绝。 */
public class AgentRequestException extends RuntimeException {

    private final AgentRun.ErrorCode errorCode;

    /** @param errorCode 对调用方稳定的拒绝原因 */
    public AgentRequestException(AgentRun.ErrorCode errorCode) {
        super(errorCode.name());
        this.errorCode = errorCode;
    }

    /** @return 稳定错误码 */
    public AgentRun.ErrorCode errorCode() {
        return errorCode;
    }
}
