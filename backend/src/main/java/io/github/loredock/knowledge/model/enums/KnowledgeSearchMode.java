package io.github.loredock.knowledge.model.enums;

/** 知识检索模式；调用方不能借此覆盖服务端候选数量、generation 或融合参数。 */
public enum KnowledgeSearchMode {
    KEYWORD,
    SEMANTIC,
    HYBRID
}
