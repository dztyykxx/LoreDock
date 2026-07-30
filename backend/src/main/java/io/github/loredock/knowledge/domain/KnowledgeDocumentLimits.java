package io.github.loredock.knowledge.domain;

/** 知识文档对外公布的字段与分页上限；领域校验、HTTP 校验和前端提示必须复用这些值。 */
public final class KnowledgeDocumentLimits {

    public static final int TITLE_MAX_CODE_POINTS = 200;
    public static final int BODY_MAX_CODE_POINTS = 2_097_152;
    public static final int DIRECTORY_MAX_CODE_POINTS = 1_000;
    public static final int TAG_MAX_COUNT = 20;
    public static final int TAG_MAX_CODE_POINTS = 100;
    public static final int WIKI_URL_MAX_CODE_POINTS = 2_000;
    public static final int ORIGINAL_FILENAME_MAX_CODE_POINTS = 512;
    public static final int CURATION_NOTE_MAX_CODE_POINTS = 2_000;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private KnowledgeDocumentLimits() {
    }
}
