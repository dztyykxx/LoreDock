package io.github.loredock.knowledge.service;

import io.github.loredock.job.api.JobService;
import io.github.loredock.knowledge.config.KnowledgeIndexJobTypes;
import io.github.loredock.knowledge.exception.KnowledgeIndexJobNotFoundException;
import io.github.loredock.knowledge.model.result.KnowledgeIndexJobView;
import org.springframework.stereotype.Service;

/** 知识重建任务应用服务；提交显式使用单实例 single-flight，查询隐藏其他任务类型和内部执行信息。 */
@Service
public class KnowledgeIndexJobService {

    private final JobService jobs;

    /** @param jobs 持久化后台任务端口 */
    public KnowledgeIndexJobService(JobService jobs) {
        this.jobs = jobs;
    }

    /**
     * @param mode 重建模式：{@link KnowledgeIndexJobTypes#REINDEX_MODE_REFRESH} 增量刷新，
     *             其他值按 {@link KnowledgeIndexJobTypes#REINDEX_MODE_FULL} 全量重建处理
     * @return 新建或复用活动任务的当前视图
     */
    public KnowledgeIndexJobView submit(String mode) {
        // 模式经 inputObjectKey 传递给后台处理器；同一任务类型单飞保证两种模式不会并发执行。
        String inputKey = KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH.equals(mode)
                ? KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH
                : KnowledgeIndexJobTypes.REINDEX_MODE_FULL;
        Long jobId = jobs.submitSingleFlight(new JobService.Request(KnowledgeIndexJobTypes.KNOWLEDGE_REINDEX, inputKey));
        return get(jobId);
    }

    public KnowledgeIndexJobView get(Long jobId) {
        JobService.Snapshot snapshot = jobs.find(jobId)
                .filter(job -> KnowledgeIndexJobTypes.KNOWLEDGE_REINDEX.equals(job.type()))
                .orElseThrow(KnowledgeIndexJobNotFoundException::new);
        String failure = snapshot.status() == JobService.Status.FAILED ? snapshot.errorMessage() : null;
        return new KnowledgeIndexJobView(
                snapshot.id(), snapshot.status(), snapshot.progress(),
                snapshot.startedAt(), snapshot.finishedAt(), failure);
    }
}
