package io.github.loredock.knowledge.api;

/** 固定知识索引版本在检索前或检索期间不再活动。 */
public class KnowledgeSearchVersionChangedException extends RuntimeException {

    public KnowledgeSearchVersionChangedException() {
        super("knowledge search index version changed");
    }
}
