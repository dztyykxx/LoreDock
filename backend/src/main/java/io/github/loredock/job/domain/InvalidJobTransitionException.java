package io.github.loredock.job.domain;

/**
 * 表示后台任务违反状态机或进度约束，供应用层转换为稳定失败码。
 */
public final class InvalidJobTransitionException extends RuntimeException {

    /**
     * @param message 不包含敏感数据的领域规则说明
     */
    public InvalidJobTransitionException(String message) {
        super(message);
    }
}
