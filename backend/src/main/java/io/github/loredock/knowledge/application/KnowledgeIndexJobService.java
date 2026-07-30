package io.github.loredock.knowledge.application;

import io.github.loredock.job.application.BackgroundJobService;
import io.github.loredock.job.application.JobRequest;
import io.github.loredock.job.domain.JobSnapshot;
import io.github.loredock.job.domain.JobStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 知识重建任务应用服务；提交显式使用单实例 single-flight，查询隐藏其他任务类型和内部执行信息。 */
@Service
public class KnowledgeIndexJobService implements KnowledgeIndexJobUseCase {

    private final BackgroundJobService jobs;

    /** @param jobs 持久化后台任务端口 */
    public KnowledgeIndexJobService(BackgroundJobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public KnowledgeIndexJobView submit() {
        UUID jobId = jobs.submitSingleFlight(new JobRequest(KnowledgeIndexJobTypes.KNOWLEDGE_REINDEX, null));
        return get(jobId);
    }

    @Override
    public KnowledgeIndexJobView get(UUID jobId) {
        JobSnapshot snapshot = jobs.find(jobId)
                .filter(job -> KnowledgeIndexJobTypes.KNOWLEDGE_REINDEX.equals(job.type()))
                .orElseThrow(KnowledgeIndexJobNotFoundException::new);
        String failure = snapshot.status() == JobStatus.FAILED ? snapshot.errorMessage() : null;
        return new KnowledgeIndexJobView(
                snapshot.id(), snapshot.status(), snapshot.progress(),
                snapshot.startedAt(), snapshot.finishedAt(), failure);
    }
}
