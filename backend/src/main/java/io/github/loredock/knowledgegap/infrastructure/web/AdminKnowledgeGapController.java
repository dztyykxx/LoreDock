package io.github.loredock.knowledgegap.infrastructure.web;

import io.github.loredock.identity.application.CurrentSessionUseCase;
import io.github.loredock.identity.domain.AuthenticatedActor;
import io.github.loredock.knowledgegap.application.AdminKnowledgeGapUseCase;
import io.github.loredock.knowledgegap.application.KnowledgeGapFilter;
import io.github.loredock.knowledgegap.application.QueryKnowledgeGapsCommand;
import io.github.loredock.knowledgegap.application.UpdateKnowledgeGapStatusCommand;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 管理员最小反馈处理入口；角色授权由统一 `/api/admin/**` 拦截链执行。 */
@RestController
@RequestMapping("/api/admin/knowledge-gaps")
public class AdminKnowledgeGapController {
    private final AdminKnowledgeGapUseCase manages;
    private final CurrentSessionUseCase sessions;

    /** @param manages 管理查询和状态用例 @param sessions 当前认证身份 */
    public AdminKnowledgeGapController(AdminKnowledgeGapUseCase manages, CurrentSessionUseCase sessions) {
        this.manages = manages;
        this.sessions = sessions;
    }

    /** @return 按项目、分支、类型和状态过滤的有界游标页 */
    @GetMapping
    public KnowledgeGapFeedbackPageResponse list(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) KnowledgeGapType type,
            @RequestParam(required = false) KnowledgeGapStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit
    ) {
        var page = manages.list(new QueryKnowledgeGapsCommand(
                new KnowledgeGapFilter(project, branch, type, status), cursor, limit));
        return new KnowledgeGapFeedbackPageResponse(
                page.items().stream().map(KnowledgeGapHttpMapper::toResponse).toList(), page.nextCursor());
    }

    /** @return 不含内部证据正文的反馈详情 */
    @GetMapping("/{feedbackId}")
    public KnowledgeGapFeedbackResponse detail(@PathVariable UUID feedbackId) {
        return KnowledgeGapHttpMapper.toResponse(manages.detail(feedbackId));
    }

    /** @return 幂等保持或单向推进后的反馈详情 */
    @PatchMapping("/{feedbackId}/status")
    public KnowledgeGapFeedbackResponse updateStatus(
            @PathVariable UUID feedbackId,
            @Valid @RequestBody UpdateKnowledgeGapStatusRequest request
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        return KnowledgeGapHttpMapper.toResponse(manages.updateStatus(
                new UpdateKnowledgeGapStatusCommand(actor.username(), feedbackId, request.status())));
    }
}
