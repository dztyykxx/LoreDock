package io.github.loredock.knowledge.model;

import java.util.List;

/**
 * 应用层在稳定锁内加载的被替代文档祖先链，用于领域层检测循环。
 *
 * @param documentIds 从直接被替代文档开始向旧文档追溯的 Long
 */
public record ReplacementChain(List<Long> documentIds) {
    public ReplacementChain {
        documentIds = List.copyOf(documentIds);
    }
}
