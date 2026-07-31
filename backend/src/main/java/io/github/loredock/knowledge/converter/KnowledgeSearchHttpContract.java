package io.github.loredock.knowledge.converter;

/**
 * 知识搜索 HTTP 契约。GET 调用幂等，只允许 ADMIN 与 MEMBER；默认模式 HYBRID、默认分支 main、默认上限 10。
 * 输入或上下文残留返回 400，未登录返回 401，未知或停用项目及未知分支返回 404；无活动索引或匹配模型返回 503。
 * 客户端不能指定 SQL、全文表达式、向量、generation、候选数量或融合权重，兼容演进只能追加可选字段。
 */
public final class KnowledgeSearchHttpContract {

    public static final String BASE_PATH = "/api/knowledge-search";
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_QUERY_CODE_POINTS = 500;
    public static final int MAX_TAGS = 10;

    private KnowledgeSearchHttpContract() {
    }
}
