package io.github.loredock.code.model.command;

import io.github.loredock.code.model.result.CodeSnapshotUpload;

/**
 * 管理员代码快照上传命令。
 *
 * @param projectId 目标项目 Long
 * @param branchId 必须属于目标项目的分支 Long
 * @param commit 管理员声明的 7～64 位十六进制 commit
 * @param upload ZIP 请求正文
 */
public record UploadCodeSnapshotCommand(
        Long projectId,
        Long branchId,
        String commit,
        CodeSnapshotUpload upload
) {
}
