package io.github.loredock.knowledge.domain;

/** 导入批次聚合状态；部分成功必须明确保留为 PARTIAL。 */
public enum ImportBatchStatus {
    COMPLETED,
    PARTIAL,
    FAILED
}
