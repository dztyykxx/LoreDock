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
 * @param sourceMessageId 本轮用户消息编号；明确更正时用于验证授权证据，可空
 * @param sourceMessage 本轮用户消息原文；只由服务端补入，不接受模型自行伪造，可空
 * @param candidates 候选列表（1~3 条）
 */
public record MemoryWriteInput(
        Long projectId,
        Long sourceRunId,
        Long sourceConversationId,
        String operatorId,
        Long sourceMessageId,
        String sourceMessage,
        List<MemoryCandidate> candidates
) {
    /** 保持旧调用方构造兼容；无消息证据时冲突只能等待确认。 */
    public MemoryWriteInput(Long projectId, Long sourceRunId, Long sourceConversationId,
            String operatorId, List<MemoryCandidate> candidates) {
        this(projectId, sourceRunId, sourceConversationId, operatorId, null, null, candidates);
    }
}
