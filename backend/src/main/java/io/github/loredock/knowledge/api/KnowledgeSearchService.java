package io.github.loredock.knowledge.api;

import java.util.Optional;

/**
 * 向其他业务模块提供的有范围知识检索契约。
 *
 * <p>索引版本标识是不透明的一致性令牌：调用方只能保存当前值并在后续检索中原样回传，
 * 不能借此选择历史索引或访问索引内部数据。</p>
 */
public interface KnowledgeSearchService {

    /**
     * 查询当前完整可用的知识索引版本。
     *
     * @return 当前版本标识；尚未建立可用索引时为空
     */
    Optional<Long> findActiveIndexVersionId();

    /**
     * 判断固定版本是否仍是当前活动版本。
     *
     * @param versionId 调用方此前保存的不透明版本标识
     * @return 仍为当前活动版本时返回 {@code true}
     */
    boolean isActiveIndexVersion(Long versionId);

    /**
     * 在指定项目与分支范围内检索已发布知识。
     *
     * @param request 服务端固定索引版本的检索请求
     * @return 有界知识结果与业务警告；无命中时结果列表为空
     * @throws KnowledgeSearchVersionChangedException 固定版本在检索前或检索期间失效
     */
    KnowledgeMatches search(KnowledgeQuery request);

    /**
     * 在全部范围内检索已发布知识：通用 + 所有项目的项目级（PROJECT）文档，不含分支文档。
     * 仅供 Agent 全局问答内部路径调用；公开检索端点不得暴露该范围。
     *
     * @param request 服务端固定索引版本的全局检索请求
     * @return 有界知识结果；结果携带真实项目标识，GLOBAL 文档项目标识为空
     * @throws KnowledgeSearchVersionChangedException 固定版本在检索前或检索期间失效
     */
    KnowledgeMatches searchAll(GlobalKnowledgeQuery request);

    /**
     * 在通用范围内检索已发布知识（GLOBAL 文档）。
     * 仅供 Agent 全局知识整理内部路径调用；公开检索端点不得暴露该范围。
     *
     * @param request 服务端固定索引版本的全局检索请求
     * @return 有界知识结果，全部为通用范围
     * @throws KnowledgeSearchVersionChangedException 固定版本在检索前或检索期间失效
     */
    KnowledgeMatches searchGlobal(GlobalKnowledgeQuery request);

    /**
     * @param query 用户检索文本
     * @param limit 服务端允许范围内的返回上限
     * @param indexVersionId 运行开始时固定的不透明活动索引版本
     */
    record GlobalKnowledgeQuery(String query, int limit, Long indexVersionId) {
    }
}
