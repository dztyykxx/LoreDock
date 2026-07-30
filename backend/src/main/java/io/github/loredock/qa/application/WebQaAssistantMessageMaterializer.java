package io.github.loredock.qa.application;

/** 把 Agent 可信完成终态幂等投影为唯一公开助手消息；失败运行不生成消息。 */
public interface WebQaAssistantMessageMaterializer {
    /** @return 新增投影为 true，已存在或非可投影终态为 false */
    boolean materialize(WebQaQuestionRecord question, io.github.loredock.agent.application.AgentRunSnapshot run);
}
