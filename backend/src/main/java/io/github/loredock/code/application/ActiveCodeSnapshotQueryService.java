package io.github.loredock.code.application;

import org.springframework.stereotype.Service;

/** 将共用活动范围解析映射为不含 generation 与物理位置的普通状态视图。 */
@Service
public class ActiveCodeSnapshotQueryService implements ActiveCodeSnapshotQueryUseCase {

    private final ActiveCodeSnapshotResolver resolver;

    /** @param resolver 已启用项目活动范围解析器 */
    public ActiveCodeSnapshotQueryService(ActiveCodeSnapshotResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ActiveCodeSnapshotView get(String projectIdentifier, String branch) {
        ResolvedCodeSnapshotScope scope = resolver.resolve(projectIdentifier, branch);
        return scope.active().map(active -> new ActiveCodeSnapshotView(
                scope.projectIdentifier(), scope.branch(), CodeSnapshotAvailability.INDEXED,
                active.snapshotId(), active.commit(), active.indexedAt(), active.indexedFileCount(),
                active.changeHint())).orElseGet(() -> new ActiveCodeSnapshotView(
                scope.projectIdentifier(), scope.branch(), CodeSnapshotAvailability.NOT_INDEXED,
                null, null, null, null, null));
    }
}
