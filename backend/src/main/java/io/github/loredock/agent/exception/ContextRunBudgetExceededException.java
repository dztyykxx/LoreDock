package io.github.loredock.agent.exception;

/**
 * 一个 run 内全部模型调用的累计输入预算耗尽：不得通过继续裁剪换取调用，
 * 由执行器失败分类转为可恢复等待并提示管理员拆分任务或缩小范围。
 */
public class ContextRunBudgetExceededException extends RuntimeException {

    public ContextRunBudgetExceededException(String message) {
        super(message);
    }
}
