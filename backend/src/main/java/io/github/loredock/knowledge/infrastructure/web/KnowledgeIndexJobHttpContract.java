package io.github.loredock.knowledge.infrastructure.web;

/** 知识重建任务 HTTP 契约；POST 返回 202，活动任务 single-flight 时仍返回同一任务。 */
public final class KnowledgeIndexJobHttpContract {

    public static final String BASE_PATH = "/api/admin/knowledge-index-jobs";

    private KnowledgeIndexJobHttpContract() {
    }
}
