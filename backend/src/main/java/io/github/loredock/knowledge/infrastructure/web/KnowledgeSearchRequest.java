package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.search.KnowledgeSearchMode;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * GET 知识搜索查询参数；条件范围组合和 Unicode code point 长度由应用用例再次按业务规则校验。
 *
 * @param query 必填纯文本查询
 * @param context 必填 GLOBAL 或 PROJECT
 * @param project PROJECT 必填项目标识，GLOBAL 禁止残留
 * @param branch PROJECT 可选分支，省略时使用 main；GLOBAL 禁止残留
 * @param mode 可选模式，省略时使用 HYBRID
 * @param tag 可重复标签，最多十个且按全部包含解释
 * @param format 可选文档格式
 * @param sourceType 可选来源类型
 * @param limit 可选结果上限，省略时使用 10
 */
public record KnowledgeSearchRequest(
        @NotBlank @Size(max = KnowledgeSearchHttpContract.MAX_QUERY_CODE_POINTS) String query,
        @NotNull KnowledgeBrowseContextType context,
        String project,
        String branch,
        KnowledgeSearchMode mode,
        @Size(max = KnowledgeSearchHttpContract.MAX_TAGS) List<@NotBlank String> tag,
        DocumentFormat format,
        DocumentSourceType sourceType,
        @Min(1) @Max(KnowledgeSearchHttpContract.MAX_LIMIT) Integer limit
) {
}
