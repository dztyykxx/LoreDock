package io.github.loredock.knowledge.application.search;

import java.util.List;

/** 在固定 generation 和强范围内生成关键词分块候选的应用输出端口。 */
public interface KnowledgeKeywordCandidatePort {

    /**
     * 查询解释完全由服务端控制；实现必须在数据库候选阶段应用范围、过滤和上限。
     *
     * @param request 固定 generation、范围、过滤和候选上限
     * @param normalizedQuery 已校验并规范化的纯文本，不是客户端全文查询语法
     * @return 按原始分数、文档 ID、分块序号稳定排序的有限候选
     */
    List<KnowledgeSearchCandidate> findCandidates(
            KnowledgeSearchCandidateRequest request,
            String normalizedQuery
    );
}
