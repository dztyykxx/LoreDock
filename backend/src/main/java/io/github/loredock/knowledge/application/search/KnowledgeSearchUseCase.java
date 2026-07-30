package io.github.loredock.knowledge.application.search;

/** 已登录 ADMIN 与 MEMBER 共用的知识检索应用入口。 */
public interface KnowledgeSearchUseCase {

    /**
     * 在服务端解析并固定的 GLOBAL 或 PROJECT 范围内执行有界搜索。
     * PROJECT 未指定分支时使用 main；调用幂等且不会触发索引、模型下载或 generation 切换。
     * 无活动搜索 generation 时明确失败为 KNOWLEDGE_INDEX_UNAVAILABLE；语义能力与活动 generation
     * 不匹配时失败为 KNOWLEDGE_EMBEDDING_UNAVAILABLE，不静默退化或扩大范围。
     *
     * @param query 纯业务查询，结果上限为 50
     * @return 固定单一 generation 的有限可引用结果
     */
    KnowledgeSearchResponse search(KnowledgeSearchQuery query);
}
