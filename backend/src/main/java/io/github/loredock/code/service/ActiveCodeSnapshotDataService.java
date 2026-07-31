package io.github.loredock.code.service;

import io.github.loredock.code.mapper.ActiveCodeSnapshotMapper;
import io.github.loredock.code.model.entity.ActiveCodeSnapshotRow;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 把显式活动连接查询映射为应用描述符，并在服务端计算有限变化提示。 */
@Service
public class ActiveCodeSnapshotDataService {

    private final ActiveCodeSnapshotMapper mapper;

    /** @param mapper 活动 snapshot/generation 连接查询 */
    public ActiveCodeSnapshotDataService(ActiveCodeSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<ActiveCodeSnapshotDescriptor> findActive(Long branchId) {
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
