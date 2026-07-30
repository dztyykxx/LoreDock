package io.github.loredock.code.application;

import java.io.IOException;
import java.io.InputStream;

/** 在全包中央目录验证成功后逐个消费普通文件的流式回调。 */
@FunctionalInterface
public interface CodeArchiveEntryConsumer {

    /**
     * 调用期间输入流有效；实现只能按受控上限读取，不能保留输入流或按 entry.path 访问文件系统。
     *
     * @param entry 已验证条目描述
     * @param input 有限生命周期的展开流
     * @throws IOException 条目内容读取失败
     */
    void accept(CodeArchiveEntry entry, InputStream input) throws IOException;
}
