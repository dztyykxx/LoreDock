package io.github.loredock.code.infrastructure.persistence;

import io.github.loredock.code.application.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.application.ActiveCodeSnapshotRepository;
import io.github.loredock.code.application.CodeSnapshotChangeHint;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** 把显式活动连接查询映射为应用描述符，并在服务端计算有限变化提示。 */
@Repository
public class MybatisPlusActiveCodeSnapshotRepository implements ActiveCodeSnapshotRepository {

    private final ActiveCodeSnapshotMapper mapper;

    /** @param mapper 活动 snapshot/generation 连接查询 */
    public MybatisPlusActiveCodeSnapshotRepository(ActiveCodeSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ActiveCodeSnapshotDescriptor> findActive(UUID branchId) {
        return Optional.ofNullable(mapper.selectActive(branchId)).map(row -> new ActiveCodeSnapshotDescriptor(
                row.getProjectId(), row.getBranchId(), row.getSnapshotId(), row.getGenerationId(),
                row.getCommitHash(), row.getIndexedAt(), row.getIndexedFileCount(), hint(row)));
    }

    private CodeSnapshotChangeHint hint(ActiveCodeSnapshotRow row) {
        if (row.getSuccessfulGenerationCount() > 1) {
            return CodeSnapshotChangeHint.UNCHANGED;
        }
        if (row.getPreviousCommitHash() == null) {
            return CodeSnapshotChangeHint.INITIAL;
        }
        return row.getCommitHash().equals(row.getPreviousCommitHash())
                ? CodeSnapshotChangeHint.UNCHANGED : CodeSnapshotChangeHint.CHANGED;
    }
}
