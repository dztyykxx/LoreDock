package io.github.loredock.knowledge.application;

/** 不可信 ZIP 的中央目录检查端口。 */
public interface ZipArchiveInspectionPort {

    /**
     * 检查已存对象的签名、结构、加密/分卷标记、路径、类型、重复名和全部资源上限。
     * 实现只能使用服务生成的单一临时文件，不得按条目名落盘或跟随链接；批次失败信息必须脱敏。
     *
     * @param objectKey 已存原始对象键，仅在受控内部传递
     * @return 可进入条目业务阶段的检查结果
     */
    ZipArchiveInspection inspect(String objectKey);

    /**
     * 在同一服务端临时文件中先完成全部批次级检查，再只读取安全 Markdown 普通文件。
     *
     * @param objectKey 已存原始对象键
     * @return 检查结果和按 ordinal 关联的候选正文
     */
    ZipArchiveReadResult inspectAndRead(String objectKey);
}
