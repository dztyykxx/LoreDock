package io.github.loredock.knowledge.converter;

/**
 * 知识文档追加式 HTTP 契约常量。普通入口只读且要求明确 GLOBAL/PROJECT 上下文；管理入口要求 ADMIN。
 * 列表固定按 {@code updatedAt DESC, id ASC} 排序。POST 创建非幂等；PUT 同值编辑、发布到当前状态和归档到当前状态幂等。
 * 管理浏览追加 {@code /browse} 子树分页，批量发布追加 {@code /batch-publish} 且整批原子、重复发布幂等；
 * 旧管理列表的 {@code directory} 仍为精确目录，不得重解释。
 * 无效字段或范围返回 400，未登录返回 401，非管理员写入返回 403，不存在或普通入口越界统一返回 404，
 * 状态和替代竞争返回 409。后续兼容变更只能追加可选字段或新端点，不得重解释既有字段与错误码。
 */
public final class KnowledgeDocumentHttpContract {

    public static final String PUBLIC_BASE_PATH = "/api/knowledge-documents";
    public static final String ADMIN_BASE_PATH = "/api/admin/knowledge-documents";
    public static final String ADMIN_BROWSE_PATH = ADMIN_BASE_PATH + "/browse";
    public static final String ADMIN_BATCH_PUBLISH_PATH = ADMIN_BASE_PATH + "/batch-publish";
    public static final String STABLE_SORT = "updatedAt,DESC;id,ASC";

    private KnowledgeDocumentHttpContract() {
    }
}
