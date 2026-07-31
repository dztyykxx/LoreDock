package io.github.loredock.agent.model.enums;

/** 可信拒答使用的稳定原因码。 */
public enum AgentRefusalReason {
    INSUFFICIENT_EVIDENCE,
    CODE_SNAPSHOT_NOT_INDEXED,
    OUT_OF_SCOPE,
    SOURCE_CONFLICT,
    AGENT_CITATION_INVALID,
    OUTPUT_POLICY_VIOLATION
}
