package io.github.loredock.code.application;

/** 在对象持久化前校验代码快照外层 ZIP 类型和真实字节边界。 */
public interface CodeSnapshotUploadValidationPort {

    /** @return 可直接交给对象存储的受限流 */
    ValidatedCodeSnapshotUpload validate(CodeSnapshotUpload upload);
}
