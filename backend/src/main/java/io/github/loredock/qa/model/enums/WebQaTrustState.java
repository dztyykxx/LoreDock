package io.github.loredock.qa.model.enums;

import io.github.loredock.agent.api.AgentRun;

/** Web 页面依据持久化 Agent 事实展示的稳定可信状态。 */
public enum WebQaTrustState {
    IN_PROGRESS,
    RELIABLE_ANSWER,
    SOURCE_CONFLICT,
    INSUFFICIENT_EVIDENCE,
    FAILED;

    /**
     * @param status Agent 运行状态
     * @param resultType 可空终态结果类型
     * @param refusalReason 可空拒答原因
     * @param errorCode 可空失败错误码
     * @return 不把活动或失败运行误标为可信回答的 Web 状态
     * @throws IllegalStateException 运行事实组合不一致
     */
    public static WebQaTrustState from(
            AgentRun.Status status,
            AgentRun.ResultType resultType,
            AgentRun.RefusalReason refusalReason,
            AgentRun.ErrorCode errorCode
    ) {
        if (status == null) {
            throw new IllegalStateException("agent run status is missing");
        }
        return switch (status) {
            case ACCEPTED, RUNNING -> {
                require(resultType == null && refusalReason == null && errorCode == null);
                yield IN_PROGRESS;
            }
            case COMPLETED -> {
                require(resultType != null && errorCode == null);
                if (resultType == AgentRun.ResultType.ANSWER) {
                    require(refusalReason == null);
                    yield RELIABLE_ANSWER;
                }
                require(refusalReason != null);
                yield refusalReason == AgentRun.RefusalReason.SOURCE_CONFLICT
                        ? SOURCE_CONFLICT : INSUFFICIENT_EVIDENCE;
            }
            case FAILED, TERMINATED -> {
                require(resultType == null && refusalReason == null && errorCode != null);
                yield FAILED;
            }
        };
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalStateException("inconsistent agent run facts");
        }
    }
}
