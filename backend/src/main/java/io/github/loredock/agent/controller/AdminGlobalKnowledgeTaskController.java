package io.github.loredock.agent.controller;

import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.auth.api.AuthService;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员全局知识整理 REST 入口：整理通用业务知识（GLOBAL 草稿 → GLOBAL 文档），
 * 不绑定项目；请求体与项目版共用同一组校验规则。
 */
@RestController
@RequestMapping("/api/admin/knowledge-tasks")
public class AdminGlobalKnowledgeTaskController {

    /** 全局知识任务的哨兵项目标识，与 KnowledgeTaskServiceImpl 保持一致。 */
    private static final String GLOBAL_PROJECT_IDENTIFIER = "GLOBAL";

    private final KnowledgeTaskService tasks;
    private final KnowledgeDraftService drafts;
    private final AuthService sessions;

    /** @param tasks 知识任务契约 @param drafts 版本化草稿契约 @param sessions 当前认证身份 */
    public AdminGlobalKnowledgeTaskController(
            KnowledgeTaskService tasks, KnowledgeDraftService drafts, AuthService sessions
    ) {
        this.tasks = tasks;
        this.drafts = drafts;
        this.sessions = sessions;
    }

    /** @return HTTP 202 的人工触发全局知识任务 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeTaskService.KnowledgeTask start(
            @Valid @RequestBody AdminKnowledgeTaskController.StartBody body
    ) {
        String operator = sessions.currentSession().username();
        return tasks.start(new KnowledgeTaskService.StartRequest(
                body.idempotencyKey(), operator, null, body.selectedDraftIds(),
                KnowledgeTaskService.TriggerType.MANUAL,
                body.triggerReason(), "knowledge-curator", body.goal()));
    }

    /** @return 当前管理员的全局知识任务摘要 */
    @GetMapping
    public List<KnowledgeTaskService.KnowledgeTaskSummary> list() {
        return tasks.listGlobal(sessions.currentSession().username());
    }

    /** @return 当前管理员可见的全局任务、消息、运行与公开事件 */
    @GetMapping("/{conversationId}")
    public KnowledgeTaskService.KnowledgeTask detail(@PathVariable Long conversationId) {
        KnowledgeTaskService.KnowledgeTask task = tasks.get(conversationId, sessions.currentSession().username());
        if (!Objects.equals(GLOBAL_PROJECT_IDENTIFIER, task.projectIdentifier())) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND);
        }
        return task;
    }

    /** @return 已投影为 PAUSE_REQUESTED 的运行 */
    @PostMapping("/{conversationId}/runs/{runId}/pause")
    public KnowledgeTaskService.KnowledgeTaskRun pause(
            @PathVariable Long conversationId, @PathVariable Long runId
    ) {
        detail(conversationId);
        return tasks.requestPause(new KnowledgeTaskService.PauseRequest(runId, sessions.currentSession().username()));
    }

    /** @return 已停止且保留已提交修订的运行 */
    @PostMapping("/{conversationId}/runs/{runId}/stop")
    public KnowledgeTaskService.KnowledgeTaskRun stop(
            @PathVariable Long conversationId, @PathVariable Long runId
    ) {
        detail(conversationId);
        return tasks.stop(new KnowledgeTaskService.StopRequest(runId, sessions.currentSession().username()));
    }

    /** @return 使用同一 threadId 恢复的运行 */
    @PostMapping("/{conversationId}/runs/{runId}/resume")
    public KnowledgeTaskService.KnowledgeTaskRun resume(
            @PathVariable Long conversationId, @PathVariable Long runId,
            @Valid @RequestBody AdminKnowledgeTaskController.GuidanceBody body
    ) {
        detail(conversationId);
        return tasks.resume(new KnowledgeTaskService.ResumeRequest(
                runId, sessions.currentSession().username(), body.guidance()));
    }

    /** @return 完成后以新 run 继续调整的受理结果 */
    @PostMapping("/{conversationId}/continue")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeTaskService.KnowledgeTaskRun continueTask(
            @PathVariable Long conversationId,
            @Valid @RequestBody AdminKnowledgeTaskController.ContinueBody body
    ) {
        detail(conversationId);
        return tasks.continueTask(new KnowledgeTaskService.ContinueRequest(
                conversationId, sessions.currentSession().username(), body.idempotencyKey(), body.guidance()));
    }

    /** @return REST 调试与无法建立 SSE 时使用的持久化增量事件 */
    @GetMapping("/{conversationId}/event-log")
    public List<KnowledgeTaskService.KnowledgeTaskEvent> events(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") long after
    ) {
        detail(conversationId);
        return tasks.events(conversationId, sessions.currentSession().username(), after);
    }

    /** @return 管理员确认无变更后的只读任务 */
    @PostMapping("/{conversationId}/close-no-change")
    public KnowledgeTaskService.KnowledgeTask closeNoChange(
            @PathVariable Long conversationId,
            @Valid @RequestBody AdminKnowledgeTaskController.CloseBody body
    ) {
        detail(conversationId);
        return tasks.closeNoChange(new KnowledgeTaskService.CloseRequest(
                conversationId, sessions.currentSession().username(), body.reason()));
    }

    /** @return 已放弃且输入恢复待整理的只读任务 */
    @PostMapping("/{conversationId}/abandon")
    public KnowledgeTaskService.KnowledgeTask abandon(
            @PathVariable Long conversationId,
            @Valid @RequestBody AdminKnowledgeTaskController.CloseBody body
    ) {
        detail(conversationId);
        return tasks.abandon(new KnowledgeTaskService.CloseRequest(
                conversationId, sessions.currentSession().username(), body.reason()));
    }

    /** @return 全工作区文档在一个事务中的发布结果（发布为通用知识文档） */
    @PostMapping("/{conversationId}/publish")
    public KnowledgeTaskService.TaskPublication publishWorkspace(
            @PathVariable Long conversationId,
            @Valid @RequestBody AdminKnowledgeTaskController.PublishWorkspaceBody body
    ) {
        detail(conversationId);
        return tasks.publish(new KnowledgeTaskService.PublishTaskRequest(
                conversationId, sessions.currentSession().username(),
                body.idempotencyKey(), body.reviewedDrafts()));
    }

    /** @return 当前或指定不可变草稿修订 */
    @GetMapping("/{conversationId}/drafts/{draftId}/revisions/{revision}")
    public KnowledgeDraftService.DraftRevision revision(
            @PathVariable Long conversationId, @PathVariable Long draftId, @PathVariable Long revision
    ) {
        var task = detail(conversationId);
        return drafts.read(new KnowledgeDraftService.ReadRequest(context(task), draftId, revision));
    }

    /** @return 按修订号升序排列的全部不可变草稿修订 */
    @GetMapping("/{conversationId}/drafts/{draftId}/revisions")
    public List<KnowledgeDraftService.DraftRevision> revisions(
            @PathVariable Long conversationId, @PathVariable Long draftId
    ) {
        var task = detail(conversationId);
        return drafts.list(new KnowledgeDraftService.ReadRequest(context(task), draftId, null));
    }

    /** @return 服务端生成的空/正式基线到已提交修订 Diff */
    @PostMapping("/{conversationId}/drafts/{draftId}/diff")
    public KnowledgeDraftService.DraftDiff diff(
            @PathVariable Long conversationId, @PathVariable Long draftId,
            @RequestBody AdminKnowledgeTaskController.DiffBody body
    ) {
        var task = detail(conversationId);
        return drafts.diff(new KnowledgeDraftService.DiffRequest(
                context(task), draftId, body.fromRevision(), body.toRevision()));
    }

    /** @return 经既有正式知识生命周期发布的明确已审核修订（发布为通用知识文档） */
    @PostMapping("/{conversationId}/drafts/{draftId}/publish")
    public KnowledgeDraftService.Publication publish(
            @PathVariable Long conversationId, @PathVariable Long draftId,
            @RequestBody AdminKnowledgeTaskController.PublishBody body
    ) {
        var task = detail(conversationId);
        return drafts.publish(new KnowledgeDraftService.PublishRequest(
                context(task), draftId, body.reviewedRevision()));
    }

    private KnowledgeDraftService.AccessContext context(KnowledgeTaskService.KnowledgeTask task) {
        Long runId = task.runs().getLast().runId();
        return new KnowledgeDraftService.AccessContext(
                sessions.currentSession().username(), task.projectIdentifier(), task.conversationId(), runId);
    }
}
