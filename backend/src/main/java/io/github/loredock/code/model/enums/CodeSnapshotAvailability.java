package io.github.loredock.code.model.enums;

/** 普通状态查询结果；NOT_INDEXED 明确表示分支存在但尚无可查询快照。 */
public enum CodeSnapshotAvailability {
    NOT_INDEXED,
    INDEXED
}
