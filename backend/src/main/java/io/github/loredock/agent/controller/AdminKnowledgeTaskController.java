package io.github.loredock.agent.controller;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 管理员知识任务会话、暂停恢复与草稿审批 REST 入口；角色授权由统一 `/api/admin/**` 拦截链完成。 */
@RestController
@RequestMapping("/api/admin/projects/{identifier}/knowledge-tasks")
public class AdminKnowledgeTaskController {

    private final KnowledgeTaskService tasks;
    private final KnowledgeDraftService drafts;
    private final AuthService sessions;

    /** @param tasks 知识任务契约 @param drafts 版本化草稿契约 @param sessions 当前认证身份 */
    public AdminKnowledgeTaskController(KnowledgeTaskService tasks, KnowledgeDraftService drafts, AuthService sessions) {
        this.tasks = tasks;
        this.drafts = drafts;
        this.sessions = sessions;
    }

    /** @return HTTP 202 的人工触发知识任务 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeTaskService.KnowledgeTask start(
            @PathVariable String identifier,
            @Valid @RequestBody StartBody body
    ) {
        String operator = sessions.currentSession().username();
        return tasks.start(new KnowledgeTaskService.StartRequest(
                body.idempotencyKey(), operator, identifier, KnowledgeTaskService.TriggerType.MANUAL,
                body.triggerReason(), "knowledge_curator", body.goal()));
    }

    /** @return 当前管理员在项目内可见的任务、消息、运行与公开事件 */
    @GetMapping("/{conversationId}")
    public KnowledgeTaskService.KnowledgeTask detail(
            @PathVariable String identifier,
            @PathVariable Long conversationId
    ) {
        KnowledgeTaskService.KnowledgeTask task = tasks.get(conversationId, sessions.currentSession().username());
        if (!Objects.equals(identifier, task.projectIdentifier())) {
            throw new io.github.loredock.agent.api.KnowledgeTaskRequestException(
                    io.github.loredock.agent.api.KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND);
        }
        return task;
    }

    /** @return 已投影为 PAUSE_REQUESTED 的运行 */
    @PostMapping("/{conversationId}/runs/{runId}/pause")
    public KnowledgeTaskService.KnowledgeTaskRun pause(
            @PathVariable String identifier, @PathVariable Long conversationId, @PathVariable Long runId
    ) {
        detail(identifier, conversationId);
        return tasks.requestPause(new KnowledgeTaskService.PauseRequest(runId, sessions.currentSession().username()));
    }

    /** @return 使用同一 threadId 恢复的运行 */
    @PostMapping("/{conversationId}/runs/{runId}/resume")
    public KnowledgeTaskService.KnowledgeTaskRun resume(
            @PathVariable String identifier, @PathVariable Long conversationId, @PathVariable Long runId,
            @Valid @RequestBody GuidanceBody body
    ) {
        detail(identifier, conversationId);
        return tasks.resume(new KnowledgeTaskService.ResumeRequest(runId, sessions.currentSession().username(), body.guidance()));
    }

    /** @return 完成后以新 run 继续调整的受理结果 */
    @PostMapping("/{conversationId}/continue")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeTaskService.KnowledgeTaskRun continueTask(
            @PathVariable String identifier, @PathVariable Long conversationId,
            @Valid @RequestBody ContinueBody body
    ) {
        detail(identifier, conversationId);
        return tasks.continueTask(new KnowledgeTaskService.ContinueRequest(
                conversationId, sessions.currentSession().username(), body.idempotencyKey(), body.guidance()));
    }

    /** @return 当前或指定不可变草稿修订 */
    @GetMapping("/{conversationId}/drafts/{draftId}/revisions/{revision}")
    public KnowledgeDraftService.DraftRevision revision(
            @PathVariable String identifier, @PathVariable Long conversationId,
            @PathVariable Long draftId, @PathVariable Long revision
    ) {
        var task = detail(identifier, conversationId);
        return drafts.read(new KnowledgeDraftService.ReadRequest(context(task), draftId, revision));
    }

    /** @return 按修订号升序排列的全部不可变草稿修订 */
    @GetMapping("/{conversationId}/drafts/{draftId}/revisions")
    public List<KnowledgeDraftService.DraftRevision> revisions(
            @PathVariable String identifier, @PathVariable Long conversationId, @PathVariable Long draftId
    ) {
        var task = detail(identifier, conversationId);
        return drafts.list(new KnowledgeDraftService.ReadRequest(context(task), draftId, null));
    }

    /** @return 服务端生成的空/正式基线到已提交修订 Diff */
    @PostMapping("/{conversationId}/drafts/{draftId}/diff")
    public KnowledgeDraftService.DraftDiff diff(
            @PathVariable String identifier, @PathVariable Long conversationId, @PathVariable Long draftId,
            @RequestBody DiffBody body
    ) {
        var task = detail(identifier, conversationId);
        return drafts.diff(new KnowledgeDraftService.DiffRequest(context(task), draftId, body.fromRevision(), body.toRevision()));
    }

    /** @return 经既有正式知识生命周期发布的明确已审核修订 */
    @PostMapping("/{conversationId}/drafts/{draftId}/publish")
    public KnowledgeDraftService.Publication publish(
            @PathVariable String identifier, @PathVariable Long conversationId, @PathVariable Long draftId,
            @RequestBody PublishBody body
    ) {
        var task = detail(identifier, conversationId);
        return drafts.publish(new KnowledgeDraftService.PublishRequest(context(task), draftId, body.reviewedRevision()));
    }

    private KnowledgeDraftService.AccessContext context(KnowledgeTaskService.KnowledgeTask task) {
        Long runId = task.runs().getLast().runId();
        return new KnowledgeDraftService.AccessContext(
                sessions.currentSession().username(), task.projectIdentifier(), task.conversationId(), runId);
    }

    /** 人工启动请求。 */
    public record StartBody(
            @NotBlank @Size(max = 128) String idempotencyKey,
            @NotBlank @Size(max = 1000) String triggerReason,
            @NotBlank @Size(max = 2000) String goal
    ) { }

    /** 暂停后的指导。 */
    public record GuidanceBody(@NotBlank @Size(max = 4000) String guidance) { }

    /** 完成后继续请求。 */
    public record ContinueBody(
            @NotBlank @Size(max = 128) String idempotencyKey,
            @NotBlank @Size(max = 4000) String guidance
    ) { }

    /** 服务端 Diff 参数。 */
    public record DiffBody(Long fromRevision, long toRevision) { }

    /** 明确审核修订发布参数。 */
    public record PublishBody(long reviewedRevision) { }
}
