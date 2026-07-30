package io.github.loredock.code.application;

import java.util.UUID;

/**
 * 管理员代码快照上传命令。
 *
 * @param projectId 目标项目 UUID
 * @param branchId 必须属于目标项目的分支 UUID
 * @param commit 管理员声明的 7～64 位十六进制 commit
 * @param upload ZIP 请求正文
 */
public record UploadCodeSnapshotCommand(
        UUID projectId,
        UUID branchId,
        String commit,
        CodeSnapshotUpload upload
) {
}
