package io.github.loredock.code.service.index;

/** Lucene 代码文档稳定字段名；身份字段只做精确匹配，正文和路径由代码 analyzer 处理。 */
public final class CodeIndexFields {

    public static final String PROJECT_ID = "project_id";
    public static final String BRANCH_ID = "branch_id";
    public static final String SNAPSHOT_ID = "snapshot_id";
    public static final String GENERATION_ID = "generation_id";
    public static final String COMMIT = "commit";
    public static final String PATH_EXACT = "path_exact";
    public static final String PATH_SORT = "path_sort";
    public static final String PATH = "path";
    public static final String FILE_NAME = "file_name";
    public static final String LANGUAGE = "language";
    public static final String CONTENT = "content";
    public static final String LINE_COUNT = "line_count";

    private CodeIndexFields() {
    }
}
