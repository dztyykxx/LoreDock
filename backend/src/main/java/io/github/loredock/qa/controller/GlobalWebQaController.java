package io.github.loredock.qa.controller;

import io.github.loredock.agent.api.AgentService;
import io.github.loredock.auth.api.AuthService;
import io.github.loredock.auth.api.AuthenticatedActor;
import io.github.loredock.platform.web.ApiError;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.platform.web.SecurityErrorFactory;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.converter.WebQaHttpMapper;
import io.github.loredock.qa.model.request.CreateWebQaQuestionRequest;
import io.github.loredock.qa.model.request.WebQaSseStreamRequest;
import io.github.loredock.qa.model.response.WebQaConversationPageResponse;
import io.github.loredock.qa.model.response.WebQaConversationResponse;
import io.github.loredock.qa.model.response.WebQaQuestionPageResponse;
import io.github.loredock.qa.model.response.WebQaQuestionResponse;
import io.github.loredock.qa.model.snapshot.WebQaSseCursor;
import io.github.loredock.qa.service.WebQaSseService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 全局（全库）问答聚合入口：不依赖 URL 项目，会话范围由服务端哨兵标识表达；
 * 检索范围固定为通用知识与所有项目的项目级已发布文档。
 */
@RestController
@RequestMapping("/api/qa")
public class GlobalWebQaController {

    /** 全局会话的哨兵项目标识，与 QaServiceImpl/AgentServiceImpl 保持一致。 */
    private static final String GLOBAL_PROJECT_IDENTIFIER = "GLOBAL";

    private final QaService questions;
    private final AgentService agents;
    private final AuthService sessions;
    private final AuthService continuity;
    private final WebQaSseService streams;
    private final SecurityErrorFactory errorFactory;

    /**
     * @param questions 全局问答创建、历史与详情契约
     * @param agents 已授权的 Agent 运行与事件查询契约
     * @param sessions 当前认证身份
     * @param continuity 异步会话租约
     * @param streams 有界 SSE 流服务
     * @param timeProvider UTC 错误时间源
     */
    public GlobalWebQaController(
            QaService questions,
            AgentService agents,
            AuthService sessions,
            AuthService continuity,
            WebQaSseService streams,
            Clock timeProvider
    ) {
        this.questions = questions;
        this.agents = agents;
        this.sessions = sessions;
        this.continuity = continuity;
        this.streams = streams;
        this.errorFactory = new SecurityErrorFactory(timeProvider);
    }

    /** @return HTTP 202 的已受理全局问答安全快照 */
    @PostMapping("/questions")
    public ResponseEntity<WebQaQuestionResponse> create(
            @Valid @RequestBody CreateWebQaQuestionRequest request
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        var snapshot = questions.createGlobal(new QaService.GlobalCreateRequest(
                actor.username(), actor.role().name(), request.idempotencyKey(),
                request.conversationId(), request.question()));
        long lastSequence = agents.lastEventSequence(snapshot.runId(), actor.username());
        return ResponseEntity.accepted().body(WebQaHttpMapper.toResponse(snapshot, lastSequence));
    }

    /** @return 当前操作者在全局范围内的问答历史 */
    @GetMapping("/questions")
    public WebQaQuestionPageResponse history(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        var page = questions.historyGlobal(new QaService.GlobalHistoryQuery(actor.username(), cursor, limit));
        var items = page.items().stream()
                .map(snapshot -> WebQaHttpMapper.toResponse(snapshot,
                        agents.lastEventSequence(snapshot.runId(), actor.username())))
                .toList();
        return new WebQaQuestionPageResponse(items, page.nextCursor());
    }

    /** @return 当前操作者可见的全局问答详情 */
    @GetMapping("/questions/{questionId}")
    public WebQaQuestionResponse detail(@PathVariable Long questionId) {
        AuthenticatedActor actor = sessions.currentSession();
        var snapshot = questions.detailGlobal(new QaService.GlobalDetailQuery(actor.username(), questionId));
        long lastSequence = agents.lastEventSequence(snapshot.runId(), actor.username());
        var events = agents.listEvents(snapshot.runId(), actor.username(), 0, 200);
        return WebQaHttpMapper.toResponse(snapshot, lastSequence, events);
    }

    /** @return 当前操作者全部范围（全局与各项目）的最近会话，含范围标注 */
    @GetMapping("/conversations")
    public WebQaConversationPageResponse conversations(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        QaService.ConversationPage page = questions.conversationsGlobal(
                new QaService.GlobalConversationHistoryQuery(actor.username(), cursor, limit));
        return new WebQaConversationPageResponse(
                page.items().stream().map(WebQaHttpMapper::toResponse).toList(), page.nextCursor());
    }

    /** @return 全局会话摘要与按稳定顺序排列的独立轮次 */
    @GetMapping("/conversations/{conversationId}")
    public WebQaConversationResponse conversation(@PathVariable Long conversationId) {
        AuthenticatedActor actor = sessions.currentSession();
        QaService.Conversation conversation = questions.conversationGlobal(
                new QaService.GlobalConversationDetailQuery(actor.username(), conversationId));
        var rounds = conversation.rounds().stream().map(round -> {
            long sequence = agents.lastEventSequence(round.runId(), actor.username());
            var events = agents.listEvents(round.runId(), actor.username(), 0, 200);
            return WebQaHttpMapper.toResponse(round, sequence, events);
        }).toList();
        return new WebQaConversationResponse(WebQaHttpMapper.toResponse(conversation.summary()), rounds);
    }

    /**
     * @param questionId 全局问答聚合标识
     * @param lastEventId 标准已消费 SSE ID
     * @param afterSequence 等价非负续读参数
     * @return text/event-stream 长连接
     */
    @GetMapping(value = "/questions/{questionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable Long questionId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) Long afterSequence
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        long cursor = WebQaSseCursor.resolve(lastEventId, afterSequence);
        var target = questions.detailGlobal(new QaService.GlobalDetailQuery(actor.username(), questionId));
        AuthService.SessionLease lease = continuity.capture();
        return streams.open(new WebQaSseStreamRequest(
                actor.username(), GLOBAL_PROJECT_IDENTIFIER, questionId, target.runId(), cursor, lease));
    }

    /**
     * SSE 尚未建立时显式返回统一 JSON 400；预设响应类型避免浏览器的 `Accept: text/event-stream` 导致 406。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidCursor(IllegalArgumentException exception) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorFactory.create(code, List.of()));
    }
}
