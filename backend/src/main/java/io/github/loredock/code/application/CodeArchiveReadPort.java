package io.github.loredock.code.application;

import java.util.UUID;

/** 从不透明原始对象执行代码 ZIP 全包校验和逐条目流式读取的基础设施端口。 */
public interface CodeArchiveReadPort {

    /**
     * 先验证完整中央目录，再按中央目录顺序消费普通文件；任一结构风险使整个调用失败且不消费任何正文。
     * 服务生成的临时 input.zip 无论成功失败都必须清理。
     *
     * @param jobId 服务端任务 UUID，只用于安全工作目录
     * @param objectKey 原始 ZIP 不透明对象键
     * @param consumer 条目流式消费者
     */
    void read(UUID jobId, String objectKey, CodeArchiveEntryConsumer consumer);
}
