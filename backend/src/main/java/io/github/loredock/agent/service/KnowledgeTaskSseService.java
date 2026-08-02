package io.github.loredock.agent.service;

import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.auth.api.AuthService;
import io.github.loredock.agent.scheduler.KnowledgeTaskSseExecutor;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 复用项目既有有界 SSE 执行器，以数据库任务事件 ID 支持续读和跨进程恢复。 */
@Service
public class KnowledgeTaskSseService {
    private final KnowledgeTaskService tasks;
    private final AuthService sessions;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final Duration MAX_DURATION = Duration.ofMinutes(5);
    private final KnowledgeTaskSseExecutor executor;
    private final Clock clock;

    public KnowledgeTaskSseService(
            KnowledgeTaskService tasks,
            AuthService sessions,
            KnowledgeTaskSseExecutor executor,
            Clock clock
    ) {
        this.tasks = tasks;
        this.sessions = sessions;
        this.executor = executor;
        this.clock = clock;
    }

    public SseEmitter open(StreamRequest request) {
        SseEmitter emitter = new SseEmitter(MAX_DURATION.toMillis());
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        if (!executor.execute(() -> stream(request, emitter, closed))) {
            emitter.complete();
            throw new IllegalStateException("知识任务 SSE 连接已满");
        }
        return emitter;
    }

    private void stream(StreamRequest request, SseEmitter emitter, AtomicBoolean closed) {
        long cursor = request.after();
        Instant deadline = clock.instant().plus(MAX_DURATION);
        Instant heartbeatAt = clock.instant().plus(HEARTBEAT_INTERVAL);
        try {
            while (!closed.get() && clock.instant().isBefore(deadline)) {
                if (!sessions.isValid(request.lease(), request.operatorId())) {
                    break;
                }
                KnowledgeTaskService.KnowledgeTask task = tasks.get(request.conversationId(), request.operatorId());
                if (!request.projectIdentifier().equals(task.projectIdentifier())) {
                    break;
                }
                List<KnowledgeTaskService.KnowledgeTaskEvent> events = tasks.events(
                        request.conversationId(), request.operatorId(), cursor);
                for (KnowledgeTaskService.KnowledgeTaskEvent event : events) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence()))
                            .name("task").data(event, MediaType.APPLICATION_JSON));
                    cursor = event.sequence();
                }
                if (!clock.instant().isBefore(heartbeatAt)) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    heartbeatAt = clock.instant().plus(HEARTBEAT_INTERVAL);
                }
                Thread.sleep(1000);
            }
        } catch (IOException ignored) {
            closed.set(true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            closed.set(true);
        } finally {
            if (closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        }
    }

    public record StreamRequest(
            String operatorId,
            String projectIdentifier,
            Long conversationId,
            long after,
            AuthService.SessionLease lease
    ) { }
}
