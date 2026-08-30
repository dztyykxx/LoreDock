package io.github.loredock.memory.api;

/**
 * 单条候选的记忆写入结论，与 {@link MemoryWriteInput} 中候选按下标一一对应。
 *
 * @param candidateIndex 候选序号（从 0 开始）
 * @param outcome 判断结论
 * @param memoryId 实际写入后的记忆编号；跳过类结论为空
 * @param message 中文理由（工具回复与日志）
 * @param conflictsWith 冲突结论命中的既有记忆编号列表；仅 CONFLICT_CREATED 时有值
 */
public record MemoryWriteVerdict(
        int candidateIndex,
        MemoryWriteOutcome outcome,
        Long memoryId,
        String message,
        long[] conflictsWith
) {
}
