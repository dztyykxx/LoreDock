package io.github.loredock.knowledge.api;

/** 版本化知识草稿读取、更新或发布被确定性拒绝。 */
public class KnowledgeDraftException extends RuntimeException {

    private final Code code;

    /**
     * @param code 稳定失败码
     */
    public KnowledgeDraftException(Code code) {
        super(code.name());
        this.code = code;
    }

    /** @return Agent、HTTP 和测试可稳定判断的失败码 */
    public Code code() {
        return code;
    }

    /** 草稿范围、修订、幂等、来源、操作和发布锁定的稳定失败语义。 */
    public enum Code {
        DRAFT_NOT_FOUND,
        DRAFT_REVISION_CONFLICT,
        DRAFT_IDEMPOTENCY_CONFLICT,
        DRAFT_SOURCE_INVALID,
        DRAFT_OPERATION_INVALID,
        DRAFT_SCOPE_VIOLATION,
        DRAFT_PUBLICATION_CONFLICT
    }
}
