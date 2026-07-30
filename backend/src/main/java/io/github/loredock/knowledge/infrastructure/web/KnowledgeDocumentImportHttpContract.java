package io.github.loredock.knowledge.infrastructure.web;

/** 导入 multipart 契约；必须先鉴权再读取 file 正文，成功批次同步返回 201。 */
public final class KnowledgeDocumentImportHttpContract {

    public static final String BASE_PATH = "/api/admin/knowledge-document-imports";
    public static final String FILE_PART = "file";
    public static final String OPTIONS_PART = "options";

    private KnowledgeDocumentImportHttpContract() {
    }
}
