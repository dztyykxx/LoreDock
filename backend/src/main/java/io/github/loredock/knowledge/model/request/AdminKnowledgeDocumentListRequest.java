package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;

/**
 * 管理员列表筛选；范围关联字段必须与 scopeType 组合一致，空筛选表示查看全部生命周期文档。
 *
 * @param scopeType 可选范围层级
 * @param projectId 可选项目 Long
 * @param branchId 可选分支 Long
 * @param directory 可选逻辑目录
 * @param status 可选生命周期状态
 * @param tag 可选标签
 * @param page 零基页码
 * @param size 页容量
 */
public record AdminKnowledgeDocumentListRequest(
        KnowledgeScopeType scopeType,
        Long projectId,
        Long branchId,
        String directory,
        DocumentStatus status,
        String tag,
        Integer page,
        Integer size
) {
}
