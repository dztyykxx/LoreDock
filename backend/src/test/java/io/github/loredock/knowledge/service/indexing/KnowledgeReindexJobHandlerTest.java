package io.github.loredock.knowledge.service.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.job.api.JobService;
import io.github.loredock.knowledge.config.KnowledgeIndexJobTypes;
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

        handler.execute(new JobService.ExecutionContext() {
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

    /**
     * 业务目的：发布自动提交携带 REFRESH 模式时必须路由到增量刷新，不得触发全量重建，
     * 防止每篇发布都重新 Embedding 全部文档。
     */
    @Test
    void refreshModeRoutesToIncrementalRefresh() throws Exception {
        Long jobId = 8000000000000000016L;
        KnowledgeIndexRebuildService rebuildService = mock(KnowledgeIndexRebuildService.class);
        when(rebuildService.refresh(eq(jobId), any()))
                .thenReturn(new KnowledgeIndexRebuildResult(8000000000000000017L, 1));
        KnowledgeReindexJobHandler handler = new KnowledgeReindexJobHandler(rebuildService);

        handler.execute(context(jobId, KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH));

        verify(rebuildService).refresh(eq(jobId), any());
        verify(rebuildService, never()).rebuild(any(), any());
    }

    /**
     * 业务目的：管理员手动提交（FULL）与历史缺省任务都必须路由到全量重建，FULL 模式语义不受刷新改动影响。
     */
    @Test
    void fullModeAndMissingModeRouteToFullRebuild() throws Exception {
        Long jobId = 8000000000000000026L;
        KnowledgeIndexRebuildService rebuildService = mock(KnowledgeIndexRebuildService.class);
        when(rebuildService.rebuild(eq(jobId), any()))
                .thenReturn(new KnowledgeIndexRebuildResult(8000000000000000027L, 1));
        KnowledgeReindexJobHandler handler = new KnowledgeReindexJobHandler(rebuildService);

        handler.execute(context(jobId, KnowledgeIndexJobTypes.REINDEX_MODE_FULL));
        handler.execute(context(jobId, null));

        verify(rebuildService, org.mockito.Mockito.times(2)).rebuild(eq(jobId), any());
        verify(rebuildService, never()).refresh(any(), any());
    }

    private JobService.ExecutionContext context(Long jobId, String inputObjectKey) {
        return new JobService.ExecutionContext() {
            @Override
            public Long jobId() {
                return jobId;
            }

            @Override
            public String inputObjectKey() {
                return inputObjectKey;
            }

            @Override
            public void updateProgress(int progress) {
            }

            @Override
            public void heartbeat() {
            }
        };
    }
}
