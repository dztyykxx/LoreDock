package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;

/**
 * 知识搜索业务输入；不包含向量、SQL、generation、候选数量或融合权重等内部参数。
 *
 * @param contextType GLOBAL 只检索通用知识，PROJECT 检索允许的三层知识
 * @param projectIdentifier PROJECT 必填的已启用项目标识
 * @param branch PROJECT 可选分支；空值由服务端固定解析为 main
 * @param query 去除首尾空白后 1～500 个 Unicode 字符的纯文本
 * @param mode 可选模式；空值默认 HYBRID
 * @param filters 两路候选共同应用的过滤条件
 * @param limit 可选返回上限；空值默认 10，合法范围 1～50
 */
public record KnowledgeSearchQuery(
        KnowledgeBrowseContextType contextType,
        String projectIdentifier,
        String branch,
        String query,
        KnowledgeSearchMode mode,
        KnowledgeSearchFilters filters,
        Integer limit
) {
}
