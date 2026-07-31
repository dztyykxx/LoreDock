package io.github.loredock.knowledge.api;

import java.util.List;

/**
 * 跨模块知识检索响应，不暴露 generation、向量或索引配置。
 *
 * @param warnings 不阻止人工知识返回的业务警告
 * @param results 当前范围内实时复核为已发布的知识结果
 */
public record KnowledgeMatches(
        List<Warning> warnings,
        List<KnowledgeMatch> results
) {
    public KnowledgeMatches {
        warnings = List.copyOf(warnings);
        results = List.copyOf(results);
    }

    /** 跨模块调用方需要保留的稳定业务警告。 */
    public enum Warning {
        CODE_SNAPSHOT_NOT_INDEXED
    }
}
