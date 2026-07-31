package io.github.loredock.code.model.entity;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 活动快照、活动 generation 与变化提示所需前驱元数据的显式查询载体。 */
@Getter
@Setter
public class ActiveCodeSnapshotRow {
    private Long projectId;
    private Long branchId;
    private Long snapshotId;
    private Long generationId;
    private String commitHash;
    private Instant indexedAt;
    private Long indexedFileCount;
    private String previousCommitHash;
    private Long successfulGenerationCount;
}
