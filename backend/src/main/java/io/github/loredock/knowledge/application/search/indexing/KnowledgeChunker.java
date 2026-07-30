package io.github.loredock.knowledge.application.search.indexing;

import java.util.List;

/** 知识投影正文的确定性分块边界，供重建流程复用并锁定版本。 */
public interface KnowledgeChunker {

    /**
     * @param body 固定 generation 的原始投影正文
     * @return 按正文顺序排列且可通过 code point 偏移追溯的分块
     */
    List<KnowledgeChunk> chunk(String body);

    /** @return 写入 generation 元数据的分块策略版本 */
    String version();
}
