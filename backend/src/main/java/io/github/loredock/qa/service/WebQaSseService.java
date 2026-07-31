package io.github.loredock.qa.service;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.auth.service.SessionService;
import io.github.loredock.qa.api.QaQuestionNotFoundException;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.config.WebQaSseProperties;
import io.github.loredock.qa.converter.WebQaSseEventMapper;
import io.github.loredock.qa.exception.WebQaSseCapacityException;
import io.github.loredock.qa.model.request.WebQaSseStreamRequest;
import io.github.loredock.qa.model.result.WebQaStreamTarget;
import io.github.loredock.qa.scheduler.BoundedWebQaSseExecutor;
import io.github.loredock.qa.service.impl.SseEmitterWebQaSink;
import io.github.loredock.qa.service.impl.WebQaSseSink;
import java.io.IOException;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 先补读持久化事件，再等待当前进程提交后通知；SSE 连接不轮询数据库。 */
@Service
@Slf4j
public class WebQaSseService {
    private final WebQaSseProperties properties;
    private final BoundedWebQaSseExecutor executor;
    private final Clock timeProvider;
    private final QaServiceImpl access;
    private final AgentService agents;
    private final SessionService sessions;
    private final DefaultWebQaAssistantMessageMaterializer materializer;

    /**
     * @param properties SSE 心跳、续读批量和容量配置
     * @param executor 专用有界连接执行器
     * @param timeProvider UTC 时间源
     * @param access 每轮问答访问复核
     * @param agents Agent 已提交事件、终态与当前进程通知契约
     * @param sessions 建连会话持续校验
     * @param materializer 终态消息投影
     */
    public WebQaSseService(
            WebQaSseProperties properties,
            BoundedWebQaSseExecutor executor,
            Clock timeProvider,
            QaServiceImpl access,
            AgentService agents,
            SessionService sessions,
            DefaultWebQaAssistantMessageMaterializer materializer
    ) {
        this.properties = properties;
        this.executor = executor;
        this.timeProvider = timeProvider;
        this.access = access;
        this.agents = agents;
        this.sessions = sessions;
        this.materializer = materializer;
    }

    /**
     * @param request 已授权且固定到问答聚合的连接请求
     * @return 由提交后事件通知和持久化补读驱动的 MVC 发射器
     * @throws WebQaSseCapacityException 专用执行器没有容量
     */
    public SseEmitter open(WebQaSseStreamRequest request) {
        SseEmitter emitter = new SseEmitter(properties.maxDuration().toMillis());
        SseEmitterWebQaSink sink = new SseEmitterWebQaSink(emitter);
        Instant openedAt = timeProvider.instant();
        if (!executor.execute(() -> stream(request, sink, openedAt))) {
            sink.complete();
            throw new WebQaSseCapacityException();
        }
        log.info("web_qa sse opened traceId={} questionId={} runId={} project={} afterSequence={}",
                traceId(request), request.questionId(), request.runId(), request.projectIdentifier(),
                request.afterSequence());
        return emitter;
    }

    void stream(WebQaSseStreamRequest request, WebQaSseSink sink, Instant openedAt) {
        long cursor = request.afterSequence();
        int sentCount = 0;
        String closeReason = "completed";
        try (var subscription = agents.subscribe(request.runId())) {
            Instant deadline = openedAt.plus(properties.maxDuration());
            while (!sink.isClosed() && timeProvider.instant().isBefore(deadline)) {
                WebQaStreamTarget target = authorizedTarget(request, sink);
                if (target == null) {
                    closeReason = "terminal_or_access_closed";
                    break;
                }
                SendResult catchUp = sendPersisted(request, cursor, sink);
                cursor = catchUp.cursor();
                sentCount += catchUp.sentCount();
                if (sink.isClosed()) {
                    closeReason = "client_disconnected";
                    break;
                }
                if (closeIfTerminal(request, target, cursor, sink)) {
                    closeReason = "terminal_or_access_closed";
                    break;
                }

                Duration remaining = Duration.between(timeProvider.instant(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    break;
                }
                Duration wait = remaining.compareTo(properties.heartbeatInterval()) < 0
                        ? remaining : properties.heartbeatInterval();
                AgentEvent event = subscription.poll(wait);
                if (event == null) {
                    sink.heartbeat();
                    continue;
                }
                if (event.sequence() == cursor + 1) {
                    sink.send(WebQaSseEventMapper.toPublic(event));
                    cursor = event.sequence();
                    sentCount++;
                }
                // 序号有缺口时下一轮从数据库补齐；重复通知由 cursor 直接忽略。
            }
            if (!sink.isClosed()) {
                closeReason = "connection_deadline";
                sink.complete();
            }
        } catch (IOException exception) {
            closeReason = "client_disconnected";
            sink.complete();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closeReason = "server_shutdown";
            sink.complete();
        } catch (RuntimeException exception) {
            closeReason = "stream_failed";
            log.warn("web_qa sse failed traceId={} questionId={} runId={} project={} branchUnknown=true "
                            + "errorCode=WEB_QA_SSE_STREAM_FAILED",
                    traceId(request), request.questionId(), request.runId(), request.projectIdentifier());
            sink.complete();
        } finally {
            log.info("web_qa sse closed traceId={} questionId={} runId={} project={} lastSequence={} "
                            + "sentCount={} closeReason={}",
                    traceId(request), request.questionId(), request.runId(), request.projectIdentifier(), cursor,
                    sentCount, closeReason);
        }
    }

    private WebQaStreamTarget authorizedTarget(WebQaSseStreamRequest request, WebQaSseSink sink) {
        if (!sessions.isValid(request.sessionLease(), request.operatorId())) {
            sink.complete();
            return null;
        }
        try {
            WebQaStreamTarget target = access.authorizeInternal(new QaService.DetailQuery(
                    request.operatorId(), request.projectIdentifier(), request.questionId()));
            if (!request.runId().equals(target.run().runId())
                    || !request.runId().equals(target.question().runId())) {
                sink.complete();
                return null;
            }
            return target;
        } catch (QaQuestionNotFoundException exception) {
            sink.complete();
            return null;
        }
    }

    private SendResult sendPersisted(WebQaSseStreamRequest request, long cursor, WebQaSseSink sink)
            throws IOException {
        long nextCursor = cursor;
        int sent = 0;
        List<AgentEvent> page;
        do {
            page = agents.listEvents(request.runId(), request.operatorId(), nextCursor, properties.batchSize());
            for (AgentEvent event : page) {
                if (!request.runId().equals(event.runId())) {
                    throw new IllegalArgumentException("SSE event run mismatch");
                }
                if (event.sequence() <= nextCursor) {
                    continue;
                }
                sink.send(WebQaSseEventMapper.toPublic(event));
                nextCursor = event.sequence();
                sent++;
            }
        } while (!sink.isClosed() && page.size() == properties.batchSize());
        return new SendResult(nextCursor, sent);
    }

    private boolean closeIfTerminal(
            WebQaSseStreamRequest request,
            WebQaStreamTarget target,
            long cursor,
            WebQaSseSink sink
    ) {
        if (!target.run().status().terminal()
                || cursor < agents.lastEventSequence(request.runId(), request.operatorId())) {
            return false;
        }
        try {
            materializer.materialize(target.question(), target.run());
        } catch (RuntimeException exception) {
            log.warn("web_qa sse terminal projection deferred questionId={} runId={} "
                            + "errorCode=WEB_QA_MESSAGE_PROJECTION_FAILED",
                    target.question().id(), target.run().runId());
        }
        sink.complete();
        return true;
    }

    private record SendResult(long cursor, int sentCount) {
    }

    private String traceId(WebQaSseStreamRequest request) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? request.questionId().toString() : current;
    }
}
