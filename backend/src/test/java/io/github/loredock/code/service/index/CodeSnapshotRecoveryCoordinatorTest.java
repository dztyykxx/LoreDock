package io.github.loredock.code.service.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.code.service.CodeSnapshotRecoveryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeSnapshotRecoveryCoordinatorTest {

    @TempDir
    Path indexRoot;

    /**
     * 业务目的：任务恢复后不得激活遗留候选；启动协调器只保留数据库活动 generation，并清理 `.building` 与无引用孤儿。
     */
    @Test
    void startupRecoveryFailsInterruptedCandidatesAndKeepsOnlyActiveGeneration() throws Exception {
        Long active = 8000000000000000123L;
        Long orphan = 8000000000000000124L;
        Long building = 8000000000000000125L;
        Files.createDirectories(indexRoot.resolve(active.toString()));
        Files.createDirectories(indexRoot.resolve(orphan.toString()));
        Files.createDirectories(indexRoot.resolve(building + ".building"));
        RecordingRecoveryService repository = new RecordingRecoveryService(Set.of(active));

        new CodeSnapshotRecoveryCoordinator(repository, indexRoot).recover();

        assertThat(repository.reconciled).isTrue();
        assertThat(indexRoot.resolve(active.toString())).isDirectory();
        assertThat(indexRoot.resolve(orphan.toString())).doesNotExist();
        assertThat(indexRoot.resolve(building + ".building")).doesNotExist();
    }

    private static final class RecordingRecoveryService extends CodeSnapshotRecoveryService {
        private final Set<Long> active;
        private boolean reconciled;

        private RecordingRecoveryService(Set<Long> active) {
            super(null, null, jobId -> true);
            this.active = active;
        }

        @Override
        public Set<Long> reconcileInterruptedBuilds() {
            reconciled = true;
            return active;
        }
    }
}
