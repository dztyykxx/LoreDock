package io.github.loredock.code.model.result;

import java.util.Optional;

/** 已启用项目、明确分支和可选活动快照的一次范围解析结果。 */
public record ResolvedCodeSnapshotScope(
        String projectIdentifier,
        Long projectId,
        String branch,
        Long branchId,
        Optional<ActiveCodeSnapshotDescriptor> active
) {
}
