package io.github.loredock.agent.application;

/** 创建 project_qa 单 Agent 运行的应用契约。 */
public interface StartProjectQaRunUseCase {

    /**
     * 校验身份、输入和项目范围后先持久化运行，再安排异步执行。
     * 相同操作者和幂等键的相同请求返回原运行；不同请求以稳定冲突失败。
     *
     * @param command 已认证操作者提交的启动命令
     * @return 新建或复用的运行快照
     */
    AgentRunSnapshot start(StartProjectQaRunCommand command);
}
