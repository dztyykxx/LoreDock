package io.github.loredock.qa.controller;

import io.github.loredock.auth.api.AuthService;
import io.github.loredock.auth.api.AuthenticatedActor;
import io.github.loredock.platform.web.ApiError;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.platform.web.SecurityErrorFactory;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.model.request.WebQaSseStreamRequest;
import io.github.loredock.qa.model.snapshot.WebQaSseCursor;
import io.github.loredock.qa.service.WebQaSseService;
import java.time.Clock;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 以问答记录为聚合根的 SSE 入口，不提供按任意 runId 订阅的 Web API。 */
@RestController
@RequestMapping("/api/projects/{identifier}/qa/questions/{questionId}/events")
public class WebQaSseController {
    private final AuthService sessions;
    private final AuthService continuity;
    private final QaService questions;
    private final WebQaSseService streams;
    private final SecurityErrorFactory errorFactory;

    /**
     * @param sessions 当前认证身份
     * @param continuity 异步会话租约
     * @param questions 问答与项目范围复核契约
     * @param streams 有界 SSE 流服务
     * @param timeProvider UTC 错误时间源
     */
    public WebQaSseController(
            AuthService sessions,
            AuthService continuity,
            QaService questions,
            WebQaSseService streams,
            Clock timeProvider
    ) {
        this.sessions = sessions;
        this.continuity = continuity;
        this.questions = questions;
        this.streams = streams;
        this.errorFactory = new SecurityErrorFactory(timeProvider);
    }

    /**
     * @param identifier URL 项目标识
     * @param questionId 问答聚合标识
     * @param lastEventId 标准已消费 SSE ID
     * @param afterSequence 等价非负续读参数
     * @return text/event-stream 长连接
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String identifier,
            @PathVariable Long questionId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) Long afterSequence
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        long cursor = WebQaSseCursor.resolve(lastEventId, afterSequence);
        var target = questions.detail(new QaService.DetailQuery(actor.username(), identifier, questionId));
        AuthService.SessionLease lease = continuity.capture();
        return streams.open(new WebQaSseStreamRequest(
                actor.username(), identifier, questionId, target.runId(), cursor, lease));
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
