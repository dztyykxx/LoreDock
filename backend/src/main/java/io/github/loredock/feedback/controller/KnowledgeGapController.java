package io.github.loredock.feedback.controller;

import io.github.loredock.auth.api.AuthService;
import io.github.loredock.auth.api.AuthenticatedActor;
import io.github.loredock.feedback.converter.KnowledgeGapHttpMapper;
import io.github.loredock.feedback.model.command.CreateKnowledgeGapCommand;
import io.github.loredock.feedback.model.request.CreateKnowledgeGapRequest;
import io.github.loredock.feedback.model.response.KnowledgeGapFeedbackResponse;
import io.github.loredock.feedback.service.CreateKnowledgeGapService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN 与 MEMBER 共用的项目知识缺口创建入口。 */
@RestController
@RequestMapping("/api/projects/{identifier}/knowledge-gaps")
public class KnowledgeGapController {
    private final CreateKnowledgeGapService creates;
    private final AuthService sessions;

    /** @param creates 事务性反馈创建用例 @param sessions 当前认证身份 */
    public KnowledgeGapController(CreateKnowledgeGapService creates, AuthService sessions) {
        this.creates = creates;
        this.sessions = sessions;
    }

    /** @return HTTP 201 的新建或幂等复用反馈 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeGapFeedbackResponse create(
            @PathVariable String identifier,
            @Valid @RequestBody CreateKnowledgeGapRequest request
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        return KnowledgeGapHttpMapper.toResponse(creates.create(CreateKnowledgeGapCommand.of(
                actor.username(), request.idempotencyKey(), identifier, request.branch(), request.type(),
                request.questionId(), request.question(), request.note())));
    }
}
