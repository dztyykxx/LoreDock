package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.identity.application.CurrentSessionUseCase;
import io.github.loredock.identity.application.WebSessionContinuityPort;
import io.github.loredock.identity.domain.AuthenticatedActor;
import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.platform.web.ApiError;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.platform.web.SecurityErrorFactory;
import io.github.loredock.qa.application.QueryWebQaDetailCommand;
import io.github.loredock.qa.application.WebQaStreamAccessUseCase;
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

import java.util.UUID;
import java.util.List;

/** 以问答记录为聚合根的 SSE 入口，不提供按任意 runId 订阅的 Web API。 */
@RestController
@RequestMapping("/api/projects/{identifier}/qa/questions/{questionId}/events")
public class WebQaSseController {
    private final CurrentSessionUseCase sessions;
    private final WebSessionContinuityPort continuity;
    private final WebQaStreamAccessUseCase access;
    private final WebQaSseService streams;
    private final SecurityErrorFactory errorFactory;

    /**
     * @param sessions 当前认证身份
     * @param continuity 异步会话租约
     * @param access 问答与项目范围复核
     * @param streams 有界 SSE 流服务
     * @param timeProvider UTC 错误时间源
     */
    public WebQaSseController(
            CurrentSessionUseCase sessions,
            WebSessionContinuityPort continuity,
            WebQaStreamAccessUseCase access,
            WebQaSseService streams,
            TimeProvider timeProvider
    ) {
        this.sessions = sessions;
        this.continuity = continuity;
        this.access = access;
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
            @PathVariable UUID questionId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) Long afterSequence
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        long cursor = WebQaSseCursor.resolve(lastEventId, afterSequence);
        var target = access.authorize(new QueryWebQaDetailCommand(actor.username(), identifier, questionId));
        WebSessionContinuityPort.Lease lease = continuity.capture();
        return streams.open(new WebQaSseStreamRequest(
                actor.username(), identifier, questionId, target.run().runId(), cursor, lease));
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
