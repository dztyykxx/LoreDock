package io.github.loredock.agent.application;

import java.util.List;
import java.util.UUID;

/** 按持久化序号续读 Agent 公开事件。 */
public interface AgentEventQueryUseCase {

    /**
     * @param runId 运行标识
     * @param operatorId 当前操作者标识
     * @param afterSequence 已消费的最后序号，零代表从头读取
     * @param limit 客户端期望数量；服务端仍会限制最大页大小
     * @return 序号严格大于 afterSequence 的有界事件
     */
    List<AgentEventSnapshot> list(UUID runId, String operatorId, long afterSequence, int limit);

    /**
     * 在复核操作者和项目访问权后读取最后提交序号，供快照与 SSE 续读收敛。
     *
     * @param runId 运行标识
     * @param operatorId 当前操作者标识
     * @return 最后事件序号；尚无事件时为零
     */
    long lastSequence(UUID runId, String operatorId);
}
