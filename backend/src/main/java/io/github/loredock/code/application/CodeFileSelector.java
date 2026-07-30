package io.github.loredock.code.application;

import java.io.InputStream;

/** 对已通过 ZIP 结构校验的普通文件应用路径、大小、二进制和严格 UTF-8 策略。 */
public interface CodeFileSelector {

    /**
     * 最多保留单文件配置上限的完整正文；规则排除或超限时不得读取/返回部分正文。
     *
     * @param entry 已验证条目描述
     * @param input 仅在本次调用有效的展开流
     * @return 完整允许文本或稳定忽略结果
     */
    CodeFileSelection select(CodeArchiveEntry entry, InputStream input);
}
