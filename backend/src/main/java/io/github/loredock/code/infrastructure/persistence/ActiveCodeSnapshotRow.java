package io.github.loredock.code.infrastructure.persistence;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 活动快照、活动 generation 与变化提示所需前驱元数据的显式查询载体。 */
@Getter
@Setter
public class ActiveCodeSnapshotRow {
    private UUID projectId;
    private UUID branchId;
    private UUID snapshotId;
    private UUID generationId;
    private String commitHash;
    private Instant indexedAt;
    private Long indexedFileCount;
    private String previousCommitHash;
    private Long successfulGenerationCount;
}
