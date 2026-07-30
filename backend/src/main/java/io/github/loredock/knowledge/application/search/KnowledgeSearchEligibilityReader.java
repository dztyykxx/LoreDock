package io.github.loredock.knowledge.application.search;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 对固定 generation 的文档候选执行事实表实时发布状态与范围资格复核。 */
public interface KnowledgeSearchEligibilityReader {

    /**
     * 这是正式返回前的第二层业务资格校验，不是允许候选端跨范围召回后的展示隐藏。
     *
     * @param candidateIds 折叠后的候选文档 ID
     * @param scope 搜索开始时解析的固定查询范围
     * @return 保留输入顺序且当前仍为 PUBLISHED 并属于该范围的文档 ID
     */
    List<UUID> retainEligible(Collection<UUID> candidateIds, KnowledgeSearchResolvedScope scope);
}
