package io.github.loredock.knowledge.application;

import java.util.UUID;

/** 管理员提交和查询全量知识重建的应用端口。 */
public interface KnowledgeIndexJobUseCase {

    /**
     * 以单实例 single-flight 语义提交：同类型 PENDING/RUNNING 已存在时返回其 ID，不创建并行任务。
     * 该保证不跨 JVM；多实例部署前必须新增数据库并发规格。
     *
     * @return 新建或复用的知识任务状态
     */
    KnowledgeIndexJobView submit();

    /**
     * 只查询 KNOWLEDGE_REINDEX 类型；不存在或属于其他任务类型时统一按知识任务不存在失败。
     *
     * @param jobId 后台任务 UUID
     * @return 脱敏任务状态
     */
    KnowledgeIndexJobView get(UUID jobId);
}
