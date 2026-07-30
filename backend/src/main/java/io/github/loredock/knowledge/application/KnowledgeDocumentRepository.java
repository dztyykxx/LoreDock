package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentRevision;
import io.github.loredock.knowledge.domain.KnowledgeDocument;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识文档持久化端口；普通读取方法必须在数据库查询阶段完成生命周期和范围隔离。
 */
public interface KnowledgeDocumentRepository {

    /**
     * 原子插入文档主体与全部标签。
     *
     * @param document 待持久化聚合
     */
    void insert(KnowledgeDocument document);

    /**
     * 以调用方已读取的修订号执行条件更新；同修订同值保存视为成功但不写数据库。
     *
     * @param document 目标聚合状态
     * @param expectedRevision 调用方读取时的修订号
     * @return 修订匹配并保存或确认同值时为 true，并发变化时为 false
     */
    boolean update(KnowledgeDocument document, DocumentRevision expectedRevision);

    /**
     * 管理端按 ID 读取任意生命周期状态。
     *
     * @param documentId 文档 UUID
     * @return 文档聚合
     */
    Optional<KnowledgeDocument> findById(UUID documentId);

    /**
     * 按 UUID 稳定顺序锁定文档，供生命周期事务避免相反锁序。
     *
     * @param documentIds 文档 UUID 集合
     * @return 已存在且按 UUID 排序的聚合
     */
    List<KnowledgeDocument> findAllByIdsForUpdate(List<UUID> documentIds);

    /**
     * 管理端分页查询，可查看全部状态但仍在 SQL 中应用明确筛选。
     *
     * @param query 管理查询
     * @return 聚合分页
     */
    PageResult<KnowledgeDocument> findAdmin(AdminKnowledgeDocumentQuery query);

    /**
     * 普通入口分页查询，仅返回当前上下文内已发布文档。
     *
     * @param query 已解析浏览查询
     * @return 聚合分页
     */
    PageResult<KnowledgeDocument> findPublished(BrowseKnowledgeDocumentsQuery query);

    /**
     * 读取当前普通上下文内已发布文档的逻辑目录路径；查询必须沿用与正文浏览相同的 SQL 范围条件。
     *
     * @param context 已解析浏览上下文
     * @return 每篇可见文档对应的目录路径，可包含重复值
     */
    List<String> findPublishedDirectoryPaths(KnowledgeBrowseContext context);

    /**
     * 普通入口按 ID 读取，跨范围、草稿和归档统一表现为空。
     *
     * @param documentId 文档 UUID
     * @param context 已解析浏览上下文
     * @return 当前上下文可见文档
     */
    Optional<KnowledgeDocument> findPublishedById(UUID documentId, KnowledgeBrowseContext context);

    /** 在数据库中按实时 PUBLISHED 与范围联合条件复核投影候选 ID。 */
    List<UUID> findPublishedEligibleIds(Collection<UUID> documentIds, KnowledgeBrowseContext context);
}
