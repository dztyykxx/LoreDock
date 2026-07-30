package io.github.loredock.agent.domain;

/** 下游可以按序消费但不包含隐藏思维链的公开运行事件类型。 */
public enum AgentEventType {
    RUN_ACCEPTED,
    RUN_STARTED,
    SKILL_PINNED,
    MODEL_STARTED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    SOURCE_FOUND,
    ANSWER_DELTA,
    REFUSAL,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_TERMINATED
}
