package io.github.loredock.knowledgegap.domain;

/** 仅表达人工处理进度的单向状态，不触发知识或索引副作用。 */
public enum KnowledgeGapStatus {
    OPEN,
    ACKNOWLEDGED,
    CLOSED;

    /** @return 目标为当前状态或紧邻下一状态时为 true */
    public boolean canMoveTo(KnowledgeGapStatus target) {
        if (target == null) {
            return false;
        }
        return target == this || switch (this) {
            case OPEN -> target == ACKNOWLEDGED;
            case ACKNOWLEDGED -> target == CLOSED;
            case CLOSED -> false;
        };
    }
}
