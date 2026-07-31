package io.github.loredock.qa.controller;

import io.github.loredock.agent.service.AgentRunQueryService;
import io.github.loredock.auth.model.AuthenticatedActor;
import io.github.loredock.auth.service.SessionService;
import io.github.loredock.qa.converter.WebQaHttpMapper;
import io.github.loredock.qa.model.command.CreateWebQaQuestionCommand;
import io.github.loredock.qa.model.command.QueryWebQaDetailCommand;
import io.github.loredock.qa.model.command.QueryWebQaHistoryCommand;
import io.github.loredock.qa.model.request.CreateWebQaQuestionRequest;
import io.github.loredock.qa.model.response.WebQaQuestionPageResponse;
import io.github.loredock.qa.model.response.WebQaQuestionResponse;
import io.github.loredock.qa.service.CreateWebQaQuestionService;
import io.github.loredock.qa.service.QueryWebQaQuestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN 与 MEMBER 共用的项目问答 REST 聚合入口。 */
@RestController
@RequestMapping("/api/projects/{identifier}/qa/questions")
public class WebQaController {
    private final CreateWebQaQuestionService creates;
    private final QueryWebQaQuestionService queries;
    private final AgentRunQueryService events;
    private final SessionService sessions;

    /**
     * @param creates 事务性问答创建用例
     * @param queries 范围约束历史与详情用例
     * @param events 已授权的最后事件序号查询
     * @param sessions 当前认证身份
     */
    public WebQaController(
            CreateWebQaQuestionService creates,
            QueryWebQaQuestionService queries,
            AgentRunQueryService events,
            SessionService sessions
    ) {
        this.creates = creates;
        this.queries = queries;
        this.events = events;
        this.sessions = sessions;
    }

    /** @return HTTP 202 的已受理问答安全快照 */
    @PostMapping
    public ResponseEntity<WebQaQuestionResponse> create(
            @PathVariable String identifier,
            @Valid @RequestBody CreateWebQaQuestionRequest request
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        var snapshot = creates.create(CreateWebQaQuestionCommand.of(
                actor.username(), actor.role().name(), request.idempotencyKey(), identifier,
                request.branch(), request.question()));
        long lastSequence = events.lastSequence(snapshot.run().runId(), actor.username());
        return ResponseEntity.accepted().body(WebQaHttpMapper.toResponse(snapshot, lastSequence));
    }

    /** @return 当前操作者在项目中的问答历史 */
    @GetMapping
    public WebQaQuestionPageResponse history(
            @PathVariable String identifier,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        var page = queries.history(new QueryWebQaHistoryCommand(
                actor.username(), identifier, cursor, limit));
        var items = page.items().stream()
                .map(snapshot -> WebQaHttpMapper.toResponse(snapshot,
                        events.lastSequence(snapshot.run().runId(), actor.username())))
                .toList();
        return new WebQaQuestionPageResponse(items, page.nextCursor());
    }

    /** @return 当前操作者可见的单条问答详情 */
    @GetMapping("/{questionId}")
    public WebQaQuestionResponse detail(
            @PathVariable String identifier,
            @PathVariable Long questionId
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        var snapshot = queries.detail(new QueryWebQaDetailCommand(actor.username(), identifier, questionId));
        long lastSequence = events.lastSequence(snapshot.run().runId(), actor.username());
        return WebQaHttpMapper.toResponse(snapshot, lastSequence);
    }
}
