package io.github.loredock.knowledge.service.indexing;

import io.github.loredock.job.api.JobService;
import io.github.loredock.knowledge.config.KnowledgeIndexJobTypes;
import io.github.loredock.knowledge.service.KnowledgeIndexRebuildService;
import org.springframework.stereotype.Component;

/** KNOWLEDGE_REINDEX 后台处理器；按提交模式协调全量重建或增量刷新、离线 Embedding、任务进度，不吞掉重建异常。 */
@Component
public class KnowledgeReindexJobHandler implements JobService.Handler {

    private final KnowledgeIndexRebuildService rebuilder;

    /** @param rebuilder 全量 generation 重建与增量刷新端口 */
    public KnowledgeReindexJobHandler(KnowledgeIndexRebuildService rebuilder) {
        this.rebuilder = rebuilder;
    }

    @Override
    public String type() {
        return KnowledgeIndexJobTypes.KNOWLEDGE_REINDEX;
    }

    @Override
    public void execute(JobService.ExecutionContext context) {
        context.updateProgress(5);
        context.heartbeat();
        // 发布自动提交携带 REFRESH 模式走增量刷新；管理员手动提交（缺省）执行全量重建。
        if (KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH.equals(context.inputObjectKey())) {
            rebuilder.refresh(context.jobId(), progress(context));
        } else {
            rebuilder.rebuild(context.jobId(), progress(context));
        }
        // 100 代表任务已终结，只能由平台在处理器正常返回后连同 SUCCEEDED 原子写入。
    }

    private KnowledgeIndexRebuildService.Progress progress(JobService.ExecutionContext context) {
        return new KnowledgeIndexRebuildService.Progress(
                percentage -> context.updateProgress(Math.max(5, Math.min(95, percentage))),
                context::heartbeat);
    }
}
