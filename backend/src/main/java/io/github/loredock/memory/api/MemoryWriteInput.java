package io.github.loredock.memory.api;

import java.util.List;

/**
 * memory_write 提炼请求：调用方（主 Agent 工具）不得指定记忆范围——
 * 范围由会话自身决定（会话挂项目→PROJECT，否则 GLOBAL）。
 *
 * <p>请求级校验失败（候选数量超限、字段超长、项目无效、预算超限、判断模型不可用）
 * 整体抛出 {@link MemoryRequestException}；候选级判断结果逐条在 {@link MemoryWriteVerdict} 返回。</p>
 *
 * @param projectId 会话归属项目；为空时本次待写候选范围为 GLOBAL
 * @param sourceRunId 提炼来源 run（溯源与写入预算归集口径）
 * @param sourceConversationId 提炼来源会话（溯源）
 * @param operatorId 会话操作者（审计）；与 run 操作者一致由调用方保证
 * @param candidates 候选列表（1~3 条）
 */
public record MemoryWriteInput(
        Long projectId,
        Long sourceRunId,
        Long sourceConversationId,
        String operatorId,
        List<MemoryCandidate> candidates
) {
}
