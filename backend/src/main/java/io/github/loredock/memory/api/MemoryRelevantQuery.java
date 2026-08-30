package io.github.loredock.memory.api;

import java.util.List;

/**
 * 摘要级检索/预载请求：只返回 {@code ACTIVE} 且范围为「{@code GLOBAL} ∪ 指定项目」的记忆。
 *
 * @param queryWords 查询词列表（调用方提供的目标文本，每条已截断限长 100 码点）；空列表表示纯热度兜底
 * @param projectId 会话归属项目；为空时只检索 GLOBAL 记忆
 * @param limit 返回上限，服务端按配置与硬上限（30）收紧，负值按默认值处理
 */
public record MemoryRelevantQuery(List<String> queryWords, Long projectId, int limit) {

    /** 查询词数量受调用方有界约束；服务端无视 null 列表。 */
    public MemoryRelevantQuery {
        queryWords = queryWords == null ? List.of() : List.copyOf(queryWords);
    }
}
