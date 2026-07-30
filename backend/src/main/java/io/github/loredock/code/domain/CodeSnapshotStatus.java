package io.github.loredock.code.domain;

/** 代码快照生命周期；只有 {@link #ACTIVE} 可进入普通查询。 */
public enum CodeSnapshotStatus {
    CANDIDATE,
    ACTIVE,
    RETIRED,
    FAILED
}
