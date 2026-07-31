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

    public KnowledgeIndexJobView submit() {
        Long jobId = jobs.submitSingleFlight(new JobService.Request(KnowledgeIndexJobTypes.KNOWLEDGE_REINDEX, null));
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
