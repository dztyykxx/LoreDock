package io.github.loredock.code.application;

import java.util.Optional;

/** 只从调用方固定活动 generation 的 Lucene StoredField 精确读取完整允许正文。 */
public interface CodeIndexSnippetPort {
    /** @return 精确范围和路径唯一命中的完整正文，不存在或跨范围时为空。 */
    Optional<String> read(ActiveCodeSnapshotDescriptor scope, String path);
}
