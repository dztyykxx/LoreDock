package io.github.loredock.code.converter;

/**
 * T4 代码快照 HTTP 契约。管理写入要求 ADMIN，普通读取要求 ADMIN 或 MEMBER；未登录为 401、越权为 403。
 * 上传和重建均非幂等并返回 202。管理分页从 0 开始、默认 20、最大 100，固定按 createdAt DESC、id ASC 排序。
 * 搜索 query 为 1～200 字符、limit 默认 10/最大 50；片段 startLine 默认 1、lineCount 默认 80/最大 200。
 * 响应不得包含代码对象键、工作目录、generation 路径或内部异常，兼容变更只能追加可选字段或新端点。
 */
public final class CodeSnapshotHttpContract {

    public static final String ADMIN_SNAPSHOT_PATH = "/api/admin/code-snapshots";
    public static final String ADMIN_JOB_PATH = "/api/admin/code-snapshot-jobs";
    public static final String PUBLIC_PROJECT_PATH = "/api/projects/{identifier}";
    public static final String STABLE_ADMIN_SORT = "createdAt,DESC;id,ASC";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_SEARCH_LIMIT = 10;
    public static final int MAX_SEARCH_LIMIT = 50;
    public static final int DEFAULT_SNIPPET_LINE_COUNT = 80;
    public static final int MAX_SNIPPET_LINE_COUNT = 200;

    private CodeSnapshotHttpContract() {
    }
}
