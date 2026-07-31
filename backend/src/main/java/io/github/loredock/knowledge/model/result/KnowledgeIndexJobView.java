package io.github.loredock.knowledge.model.result;

import io.github.loredock.job.api.JobService;
import java.time.Instant;

/** 对管理员可见的知识重建任务状态，不包含 owner、对象键或内部异常。 */
public record KnowledgeIndexJobView(
        Long id,
        JobService.Status status,
        int progress,
        Instant startedAt,
        Instant finishedAt,
        String failureSummary
) {
}
