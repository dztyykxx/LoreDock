package io.github.loredock.code.model.enums;

/** 当前活动快照相对前一成功活动版本的轻量变化提示，不代表完整代码 diff。 */
public enum CodeSnapshotChangeHint {
    INITIAL,
    CHANGED,
    UNCHANGED
}
