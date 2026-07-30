package io.github.loredock.knowledge.infrastructure.indexing;

import io.github.loredock.job.application.JobExecutionContext;
import io.github.loredock.job.application.JobHandler;
import io.github.loredock.knowledge.application.KnowledgeIndexJobTypes;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildProgress;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuilder;
import org.springframework.stereotype.Component;

/** KNOWLEDGE_REINDEX 后台处理器；只协调本地 PostgreSQL 投影构建和任务进度，不吞掉重建异常。 */
@Component
public class KnowledgeReindexJobHandler implements JobHandler {

    private final KnowledgeIndexRebuilder rebuilder;

    /** @param rebuilder REPEATABLE READ generation 重建端口 */
    public KnowledgeReindexJobHandler(KnowledgeIndexRebuilder rebuilder) {
        this.rebuilder = rebuilder;
    }

    @Override
    public String type() {
        return KnowledgeIndexJobTypes.KNOWLEDGE_REINDEX;
    }

    @Override
    public void execute(JobExecutionContext context) {
        context.updateProgress(5);
        context.heartbeat();
        rebuilder.rebuild(context.jobId(), new KnowledgeIndexRebuildProgress() {
            @Override
            public void update(int percentage) {
                context.updateProgress(Math.max(5, Math.min(95, percentage)));
            }

            @Override
            public void heartbeat() {
                context.heartbeat();
            }
        });
        // 100 代表任务已终结，只能由平台在处理器正常返回后连同 SUCCEEDED 原子写入。
    }
}
