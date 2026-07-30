package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.agent.application.AgentEventQueryUseCase;
import io.github.loredock.identity.application.WebSessionContinuityPort;
import io.github.loredock.qa.application.WebQaAssistantMessageMaterializer;
import io.github.loredock.qa.application.QueryWebQaDetailCommand;
import io.github.loredock.qa.application.WebQaQuestionNotFoundException;
import io.github.loredock.qa.application.WebQaStreamAccessUseCase;
import io.github.loredock.qa.application.WebQaStreamTarget;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/** 执行一次会话/范围复核、已提交事件续读、心跳或终态收敛。 */
@Slf4j
final class WebQaSsePoller {
    private final WebQaStreamAccessUseCase access;
    private final AgentEventQueryUseCase events;
    private final WebSessionContinuityPort sessions;
    private final WebQaAssistantMessageMaterializer materializer;
    private final int batchSize;
    private final Duration heartbeatInterval;

    /**
     * @param access 每轮问答与项目访问复核
     * @param events 已授权事件续读
     * @param sessions 长连接会话存活复核
     * @param materializer 终态助手消息自愈投影
     * @param batchSize 单轮事件上限
     * @param heartbeatInterval 无业务事件心跳间隔
     */
    WebQaSsePoller(
            WebQaStreamAccessUseCase access,
            AgentEventQueryUseCase events,
            WebSessionContinuityPort sessions,
            WebQaAssistantMessageMaterializer materializer,
            int batchSize,
            Duration heartbeatInterval
    ) {
        if (batchSize < 1 || batchSize > 200 || heartbeatInterval == null
                || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("SSE poller configuration invalid");
        }
        this.access = access;
        this.events = events;
        this.sessions = sessions;
        this.materializer = materializer;
        this.batchSize = batchSize;
        this.heartbeatInterval = heartbeatInterval;
    }

    /** @return 本轮发送后的可持久续读状态 */
    WebQaSsePollResult poll(
            WebQaSseStreamRequest request,
            long cursor,
            Instant lastEmissionAt,
            Instant now,
            WebQaSseSink sink
    ) throws IOException {
        if (sink.isClosed()) {
            return new WebQaSsePollResult(cursor, lastEmissionAt, true, 0);
        }
        if (!sessions.isValid(request.sessionLease(), request.operatorId())) {
            sink.complete();
            return new WebQaSsePollResult(cursor, lastEmissionAt, true, 0);
        }

        WebQaStreamTarget target;
        try {
            target = access.authorize(new QueryWebQaDetailCommand(
                    request.operatorId(), request.projectIdentifier(), request.questionId()));
        } catch (WebQaQuestionNotFoundException exception) {
            // 长连接建立后项目停用、归属变化或记录消失均只关闭连接，不暴露哪个条件发生。
            sink.complete();
            return new WebQaSsePollResult(cursor, lastEmissionAt, true, 0);
        }
        if (!request.runId().equals(target.run().runId())
                || !request.runId().equals(target.question().runId())) {
            sink.complete();
            return new WebQaSsePollResult(cursor, lastEmissionAt, true, 0);
        }

        long nextCursor = cursor;
        int sentCount = 0;
        for (var event : events.list(request.runId(), request.operatorId(), cursor, batchSize)) {
            if (!request.runId().equals(event.runId())) {
                throw new IllegalArgumentException("SSE event run mismatch");
            }
            if (event.sequence() <= nextCursor) {
                // 防御性忽略同序号重放或乱序旧行，浏览器永远只看到严格递增 ID。
                continue;
            }
            sink.send(WebQaSseEventMapper.toPublic(event));
            nextCursor = event.sequence();
            sentCount++;
        }

        Instant nextEmissionAt = sentCount == 0 ? lastEmissionAt : now;
        if (target.run().status().terminal()
                && nextCursor >= events.lastSequence(request.runId(), request.operatorId())) {
            try {
                materializer.materialize(target.question(), target.run());
            } catch (RuntimeException exception) {
                // Agent 终态仍是事实来源；投影失败由后续详情读取自愈，SSE 不重写终态。
                log.warn("web_qa sse terminal projection deferred questionId={} runId={} "
                                + "errorCode=WEB_QA_MESSAGE_PROJECTION_FAILED",
                        target.question().id(), target.run().runId());
            }
            sink.complete();
            return new WebQaSsePollResult(nextCursor, nextEmissionAt, true, sentCount);
        }
        if (sentCount == 0 && !now.isBefore(lastEmissionAt.plus(heartbeatInterval))) {
            sink.heartbeat();
            nextEmissionAt = now;
        }
        return new WebQaSsePollResult(nextCursor, nextEmissionAt, sink.isClosed(), sentCount);
    }
}
