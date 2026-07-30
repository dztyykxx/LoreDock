package io.github.loredock.knowledge.infrastructure.indexing;

import io.github.loredock.job.application.JobExecutionContext;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeReindexJobHandlerTest {

    /**
     * 业务目的：处理器只能报告 0 到 99 的运行进度，100 必须由任务平台在成功终结时写入，
     * 防止索引已切换却因违反任务状态机而被记为失败。
     */
    @Test
    void successfulRebuildLeavesCompletionProgressToJobPlatform() {
        List<Integer> reportedProgress = new ArrayList<>();
        UUID jobId = UUID.randomUUID();
        KnowledgeReindexJobHandler handler = new KnowledgeReindexJobHandler((id, progress) -> {
            progress.update(95);
            return new KnowledgeIndexRebuildResult(UUID.randomUUID(), 1);
        });

        handler.execute(new JobExecutionContext() {
            @Override
            public UUID jobId() {
                return jobId;
            }

            @Override
            public String inputObjectKey() {
                return null;
            }

            @Override
            public void updateProgress(int progress) {
                if (progress > 99) {
                    throw new IllegalArgumentException("running progress must stay below 100");
                }
                reportedProgress.add(progress);
            }

            @Override
            public void heartbeat() {
            }
        });

        assertThat(reportedProgress).containsExactly(5, 95);
    }
}
