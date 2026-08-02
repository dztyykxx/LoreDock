package io.github.loredock.agent.api;

/** 知识任务请求在模型调用前或状态变更时被确定性拒绝。 */
public class KnowledgeTaskRequestException extends RuntimeException {

    private final Code code;

    /**
     * @param code 稳定失败码
     */
    public KnowledgeTaskRequestException(Code code) {
        super(code.name());
        this.code = code;
    }

    /** @return 页面和 Tool 可稳定判断的失败码 */
    public Code code() {
        return code;
    }

    /** 知识任务入口、定义预检与安全暂停的稳定失败语义。 */
    public enum Code {
        KNOWLEDGE_TASK_NOT_FOUND,
        KNOWLEDGE_TASK_IDEMPOTENCY_CONFLICT,
        KNOWLEDGE_TASK_DRAFT_SELECTION_INVALID,
        KNOWLEDGE_TASK_NOT_PAUSABLE,
        KNOWLEDGE_TASK_NOT_WAITING_FOR_USER,
        KNOWLEDGE_TASK_NOT_CONTINUABLE,
        AGENT_DEFINITION_INVALID,
        AGENT_CHECKPOINT_UNAVAILABLE
    }
}
