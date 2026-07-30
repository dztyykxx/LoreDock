package io.github.loredock.knowledge.domain;

import java.util.List;
import java.util.UUID;

/**
 * 应用层在稳定锁内加载的被替代文档祖先链，用于领域层检测循环。
 *
 * @param documentIds 从直接被替代文档开始向旧文档追溯的 UUID
 */
public record ReplacementChain(List<UUID> documentIds) {
    public ReplacementChain {
        documentIds = List.copyOf(documentIds);
    }
}
