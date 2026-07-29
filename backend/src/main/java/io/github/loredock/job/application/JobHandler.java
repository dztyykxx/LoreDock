package io.github.loredock.job.application;

/**
 * 已注册后台任务类型的受控工作单元。
 */
public interface JobHandler {

    /** @return 唯一任务类型标识。 */
    String type();

    /**
     * 执行任务；异常由平台边界统一分类、脱敏并转为 FAILED，不得自行吞掉。
     *
     * @param context 进度、心跳和输入上下文
     * @throws Exception 工作失败
     */
    void execute(JobExecutionContext context) throws Exception;
}
