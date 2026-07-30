package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.agent.application.AgentEventQueryUseCase;
import io.github.loredock.identity.application.WebSessionContinuityPort;
import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.qa.application.WebQaAssistantMessageMaterializer;
import io.github.loredock.qa.application.WebQaStreamAccessUseCase;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;

/** 用专用有界工作池驱动持久化事件轮询，连接失败不修改 Agent 运行事实。 */
@Service
@Slf4j
public class WebQaSseService {
    private final WebQaSseProperties properties;
    private final BoundedWebQaSseExecutor executor;
    private final TimeProvider timeProvider;
    private final WebQaSsePoller poller;

    /**
     * @param properties SSE 轮询和容量配置
     * @param executor 专用有界连接执行器
     * @param timeProvider UTC 时间源
     * @param access 每轮问答访问复核
     * @param events Agent 已提交事件查询
     * @param sessions 建连会话持续校验
     * @param materializer 终态消息投影
     */
    public WebQaSseService(
            WebQaSseProperties properties,
            BoundedWebQaSseExecutor executor,
            TimeProvider timeProvider,
            WebQaStreamAccessUseCase access,
            AgentEventQueryUseCase events,
            WebSessionContinuityPort sessions,
            WebQaAssistantMessageMaterializer materializer
    ) {
        this.properties = properties;
        this.executor = executor;
        this.timeProvider = timeProvider;
        this.poller = new WebQaSsePoller(
                access, events, sessions, materializer,
                properties.batchSize(), properties.heartbeatInterval());
    }

    /**
     * @param request 已授权且固定到问答聚合的连接请求
     * @return 由后台有限轮询驱动的 MVC 发射器
     * @throws WebQaSseCapacityException 专用执行器没有容量
     */
    SseEmitter open(WebQaSseStreamRequest request) {
        SseEmitter emitter = new SseEmitter(properties.maxDuration().toMillis());
        SseEmitterWebQaSink sink = new SseEmitterWebQaSink(emitter);
        Instant openedAt = timeProvider.now();
        if (!executor.execute(() -> stream(request, sink, openedAt))) {
            sink.complete();
            throw new WebQaSseCapacityException();
        }
        log.info("web_qa sse opened traceId={} questionId={} runId={} project={} afterSequence={}",
                traceId(request), request.questionId(), request.runId(), request.projectIdentifier(),
                request.afterSequence());
        return emitter;
    }

    private void stream(WebQaSseStreamRequest request, SseEmitterWebQaSink sink, Instant openedAt) {
        long cursor = request.afterSequence();
        Instant lastEmissionAt = openedAt;
        int sentCount = 0;
        String closeReason = "completed";
        try {
            Instant deadline = openedAt.plus(properties.maxDuration());
            while (!sink.isClosed() && timeProvider.now().isBefore(deadline)) {
                WebQaSsePollResult result = poller.poll(
                        request, cursor, lastEmissionAt, timeProvider.now(), sink);
                cursor = result.cursor();
                lastEmissionAt = result.lastEmissionAt();
                sentCount += result.sentCount();
                if (result.closed()) {
                    closeReason = "terminal_or_access_closed";
                    break;
                }
                Thread.sleep(properties.pollInterval().toMillis());
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
            closeReason = "poll_failed";
            log.warn("web_qa sse failed traceId={} questionId={} runId={} project={} branchUnknown=true "
                            + "errorCode=WEB_QA_SSE_POLL_FAILED",
                    traceId(request), request.questionId(), request.runId(), request.projectIdentifier());
            sink.complete();
        } finally {
            log.info("web_qa sse closed traceId={} questionId={} runId={} project={} lastSequence={} "
                            + "sentCount={} closeReason={}",
                    traceId(request), request.questionId(), request.runId(), request.projectIdentifier(), cursor,
                    sentCount, closeReason);
        }
    }

    private String traceId(WebQaSseStreamRequest request) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? request.questionId().toString() : current;
    }
}
