package io.github.loredock.code.application;

import java.util.Optional;
import java.util.UUID;

/** 已启用项目、明确分支和可选活动快照的一次范围解析结果。 */
public record ResolvedCodeSnapshotScope(
        String projectIdentifier,
        UUID projectId,
        String branch,
        UUID branchId,
        Optional<ActiveCodeSnapshotDescriptor> active
) {
}
