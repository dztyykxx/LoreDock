package io.github.loredock.code.api;

/** 固定代码快照在搜索或读取期间不再活动。 */
public class CodeSnapshotVersionChangedException extends RuntimeException {

    public CodeSnapshotVersionChangedException() {
        super("active code snapshot version changed");
    }
}
