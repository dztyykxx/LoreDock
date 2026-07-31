package io.github.loredock.knowledge.service.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.job.service.JobExecutionContext;
import io.github.loredock.knowledge.model.result.KnowledgeIndexRebuildResult;
import io.github.loredock.knowledge.service.KnowledgeIndexRebuildService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeReindexJobHandlerTest {

    /**
     * 业务目的：处理器只能报告 0 到 99 的运行进度，100 必须由任务平台在成功终结时写入，
     * 防止索引已切换却因违反任务状态机而被记为失败。
     */
    @Test
    void successfulRebuildLeavesCompletionProgressToJobPlatform() {
        List<Integer> reportedProgress = new ArrayList<>();
        Long jobId = 8000000000000000006L;
        KnowledgeIndexRebuildService rebuildService = mock(KnowledgeIndexRebuildService.class);
        when(rebuildService.rebuild(eq(jobId), any())).thenAnswer(invocation -> {
            KnowledgeIndexRebuildService.Progress progress = invocation.getArgument(1);
            progress.update(95);
            return new KnowledgeIndexRebuildResult(8000000000000000007L, 1);
        });
        KnowledgeReindexJobHandler handler = new KnowledgeReindexJobHandler(rebuildService);

        handler.execute(new JobExecutionContext() {
            @Override
            public Long jobId() {
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
