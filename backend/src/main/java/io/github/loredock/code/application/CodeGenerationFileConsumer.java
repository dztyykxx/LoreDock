package io.github.loredock.code.application;

import java.io.IOException;

/** 将单个已完成安全选择的代码文件直接交给 generation writer 的流式边界。 */
@FunctionalInterface
public interface CodeGenerationFileConsumer {

    /**
     * @param file 只含规范仓库路径和单文件上限内完整正文的代码文件
     * @throws IOException Lucene 或文件系统写入失败
     */
    void accept(CodeGenerationFile file) throws IOException;
}
