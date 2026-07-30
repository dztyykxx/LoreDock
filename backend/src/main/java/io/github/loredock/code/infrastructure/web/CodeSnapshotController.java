package io.github.loredock.code.infrastructure.web;

import io.github.loredock.code.application.ActiveCodeSnapshotQueryUseCase;
import io.github.loredock.code.application.ActiveCodeSnapshotView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN 与 MEMBER 共用的普通代码快照状态入口。 */
@RestController
public class CodeSnapshotController {

    private final ActiveCodeSnapshotQueryUseCase snapshots;

    /** @param snapshots 已启用项目的活动快照状态用例 */
    public CodeSnapshotController(ActiveCodeSnapshotQueryUseCase snapshots) {
        this.snapshots = snapshots;
    }

    /** @return 指定项目和明确分支的活动摘要或 NOT_INDEXED。 */
    @GetMapping("/api/projects/{identifier}/code-snapshot")
    public ActiveCodeSnapshotResponse get(
            @PathVariable String identifier,
            @RequestParam(required = false) String branch
    ) {
        ActiveCodeSnapshotView view = snapshots.get(identifier, branch);
        return new ActiveCodeSnapshotResponse(
                view.projectIdentifier(), view.branch(), view.status(), view.snapshotId(), view.commit(),
                view.indexedAt(), view.indexedFileCount(), view.changeHint());
    }
}
