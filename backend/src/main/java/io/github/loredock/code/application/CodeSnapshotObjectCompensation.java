package io.github.loredock.code.application;

/** 对象成功发布但快照/任务事务未提交时的幂等补偿端口。 */
public interface CodeSnapshotObjectCompensation {

    /** 删除没有快照引用的原始对象；补偿失败不得覆盖原始事务失败。 */
    void deleteUnreferenced(String objectKey);
}
