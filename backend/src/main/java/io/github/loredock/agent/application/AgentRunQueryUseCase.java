package io.github.loredock.agent.application;

import java.util.UUID;

/** 按当前操作者和项目访问范围读取运行事实。 */
public interface AgentRunQueryUseCase {

    /**
     * @param runId 运行标识
     * @param operatorId 当前操作者标识
     * @return 数据库中的最新运行快照和最终引用
     * @throws AgentRunNotFoundException 运行不存在或当前操作者无权读取
     */
    AgentRunSnapshot get(UUID runId, String operatorId);
}
