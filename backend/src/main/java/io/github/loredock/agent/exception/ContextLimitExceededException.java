package io.github.loredock.agent.exception;

/**
 * 单次模型调用预算超限且无法通过保守裁剪降下来：调用方不得发送模型请求。
 *
 * <p>该异常不是模型解析失败，不进入同输入重试回路；由执行器失败分类转为可恢复等待
 * （WAITING_FOR_USER + 保留 Checkpoint），管理员缩小范围后可继续。</p>
 */
public class ContextLimitExceededException extends RuntimeException {

    public ContextLimitExceededException(String message) {
        super(message);
    }
}
