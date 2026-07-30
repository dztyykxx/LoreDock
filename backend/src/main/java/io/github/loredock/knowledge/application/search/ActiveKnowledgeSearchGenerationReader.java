package io.github.loredock.knowledge.application.search;

import java.util.Optional;

/** 读取已完整校验并原子激活的知识搜索 generation。 */
public interface ActiveKnowledgeSearchGenerationReader {

    /**
     * @return 当前完整活动 generation；首次重建前或旧 generation 缺少搜索元数据时为空
     */
    Optional<ActiveKnowledgeSearchGeneration> findActive();
}
