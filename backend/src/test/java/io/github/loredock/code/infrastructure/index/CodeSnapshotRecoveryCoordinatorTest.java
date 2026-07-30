package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.CodeSnapshotRecoveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSnapshotRecoveryCoordinatorTest {

    @TempDir
    Path indexRoot;

    /**
     * 业务目的：任务恢复后不得激活遗留候选；启动协调器只保留数据库活动 generation，并清理 `.building` 与无引用孤儿。
     */
    @Test
    void startupRecoveryFailsInterruptedCandidatesAndKeepsOnlyActiveGeneration() throws Exception {
        UUID active = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        Files.createDirectories(indexRoot.resolve(active.toString()));
        Files.createDirectories(indexRoot.resolve(orphan.toString()));
        Files.createDirectories(indexRoot.resolve(building + ".building"));
        RecordingRecoveryRepository repository = new RecordingRecoveryRepository(Set.of(active));

        new CodeSnapshotRecoveryCoordinator(repository, indexRoot).recover();

        assertThat(repository.reconciled).isTrue();
        assertThat(indexRoot.resolve(active.toString())).isDirectory();
        assertThat(indexRoot.resolve(orphan.toString())).doesNotExist();
        assertThat(indexRoot.resolve(building + ".building")).doesNotExist();
    }

    private static final class RecordingRecoveryRepository implements CodeSnapshotRecoveryRepository {
        private final Set<UUID> active;
        private boolean reconciled;

        private RecordingRecoveryRepository(Set<UUID> active) { this.active = active; }

        @Override
        public Set<UUID> reconcileInterruptedBuilds() {
            reconciled = true;
            return active;
        }
    }
}
