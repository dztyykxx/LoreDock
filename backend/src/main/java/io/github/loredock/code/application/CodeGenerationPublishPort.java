package io.github.loredock.code.application;

/** 代码 generation 文件系统发布端口。 */
public interface CodeGenerationPublishPort {

    /**
     * 先写入不可查询的 `.building` 目录，关闭并重开验证，再原子发布为 generation UUID 目录。
     * 数据库只能在该方法成功返回后保存或激活 generation；失败时不得指向候选目录。
     *
     * @param request 服务端生成 ID、固定业务范围与已完成安全选择的文件
     * @return 不含物理路径的已发布 generation 摘要
     */
    PublishedCodeGeneration publish(CodeGenerationBuildRequest request);

    /**
     * 在 writer 生命周期内逐文件读取并写入，生产路径只同时保留当前单文件正文。
     *
     * @param scope 不含文件正文的固定 generation 业务范围
     * @param producer 已通过 ZIP 全包校验后的逐文件生产者
     * @return 不含物理路径的已发布 generation 摘要
     */
    PublishedCodeGeneration publishStreaming(
            CodeGenerationBuildRequest scope,
            CodeGenerationFileProducer producer
    );
}
