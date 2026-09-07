package io.github.loredock.memory.api;

/** 单条候选的记忆写入结论，与候选按下标一一对应。 */
public record MemoryWriteVerdict(
        int candidateIndex,
        MemoryWriteOutcome outcome,
        Long memoryId,
        String message,
        long[] conflictsWith,
        MemoryWriteRelation relation,
        Long targetMemoryId,
        Long revision,
        java.util.List<String> changes,
        java.util.List<String> conflicts,
        String recommendation,
        String question
) {
    /** 保持既有模块测试和调用方的五参数构造兼容。 */
    public MemoryWriteVerdict(int candidateIndex, MemoryWriteOutcome outcome, Long memoryId,
            String message, long[] conflictsWith) {
        this(candidateIndex, outcome, memoryId, message, conflictsWith, null,
                null, null, java.util.List.of(), java.util.List.of(), null, null);
    }
}
