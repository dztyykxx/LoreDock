package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.application.KnowledgeIndexJobUseCase;
import io.github.loredock.knowledge.application.KnowledgeIndexJobView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 管理员知识 generation 重建任务入口；`/api/admin/**` 统一授权保护提交和查询。 */
@RestController
@RequestMapping(KnowledgeIndexJobHttpContract.BASE_PATH)
public class KnowledgeIndexJobController {

    private final KnowledgeIndexJobUseCase jobs;

    /** @param jobs 知识重建任务用例 */
    public KnowledgeIndexJobController(KnowledgeIndexJobUseCase jobs) {
        this.jobs = jobs;
    }

    /** @return 新建或复用活动任务的当前视图 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeIndexJobResponse submit() {
        return response(jobs.submit());
    }

    /** @return 指定知识任务当前持久状态 */
    @GetMapping("/{jobId}")
    public KnowledgeIndexJobResponse get(@PathVariable UUID jobId) {
        return response(jobs.get(jobId));
    }

    private KnowledgeIndexJobResponse response(KnowledgeIndexJobView view) {
        return new KnowledgeIndexJobResponse(
                view.id(), view.status(), view.progress(), view.startedAt(), view.finishedAt(), view.failureSummary());
    }
}
