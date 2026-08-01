package io.github.loredock.agent.model.enums;

/** 下游可以按序消费但不包含隐藏思维链的公开运行事件类型。 */
public enum AgentEventType {
    RUN_ACCEPTED,
    RUN_STARTED,
    MODEL_STARTED,
    SOURCE_FOUND,
    AGENT_STAGE,
    MODEL_STAGE,
    TOOL_STARTED,
    TOOL_COMPLETED,
    SOURCE_DISCOVERED,
    CITATION_VALIDATION,
    PUBLIC_DECISION_SUMMARY,
    ANSWER_DELTA,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_TERMINATED
}
