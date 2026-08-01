package io.github.loredock.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.mapper.AgentRunEventMapper;
import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.model.entity.AgentRunEventEntity;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.snapshot.AgentEventSnapshot;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Agent 事件服务，每次 append 是独立短事务并在提交后才可被查询。 */
@Service
@Slf4j
public class AgentEventService {

    private final AgentRunEventMapper events;
    private final ObjectMapper objectMapper;
    private final Map<Long, CopyOnWriteArrayList<BlockingQueue<AgentEventSnapshot>>> subscribers =
            new ConcurrentHashMap<>();

    /** @param events 事件 Mapper @param objectMapper JSON 序列化器 */
    public AgentEventService(AgentRunEventMapper events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentEventSnapshot append(Long runId, AgentEventType type, String safePayload, Instant createdAt) {
        AgentRunEventEntity entity = events.appendReturning(
                runId, type.name(), legacySubject(type), json(safePayload), createdAt);
        log.debug("agent_event persisted runId={} sequence={} eventType={} payloadLength={}",
                runId, entity.getSequence(), type, safePayload == null ? 0 : safePayload.length());
        AgentEventSnapshot snapshot = snapshot(entity);
        publishAfterCommit(snapshot);
        return snapshot;
    }

    /**
     * 运行仍为 RUNNING 时持久化经过字段白名单构造的类型化公开事件。
     *
     * @return 事件是否成功追加；终态后的迟到事件返回 false
     */
    @Transactional
    public boolean append(
            Long runId,
            AgentEventType type,
            AgentEvent.SubjectType subjectType,
            AgentEvent.Payload payload,
            Instant createdAt
    ) {
        Objects.requireNonNull(subjectType, "event subject type");
        Objects.requireNonNull(payload, "event payload");
        AgentRunEventEntity entity = events.appendWhileRunningReturning(
                runId, type.name(), subjectType.name(), typedJson(payload), createdAt);
        if (entity == null) {
            log.debug("agent_typed_event ignored runId={} eventType={} reason=run_not_running", runId, type);
            return false;
        }
        AgentEventSnapshot snapshot = snapshot(entity);
        publishAfterCommit(snapshot);
        return true;
    }

    @Transactional(readOnly = true)
    public List<AgentEventSnapshot> findAfter(Long runId, long afterSequence, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 200);
        return events.selectList(new LambdaQueryWrapper<AgentRunEventEntity>()
                        .eq(AgentRunEventEntity::getRunId, runId)
                        .gt(AgentRunEventEntity::getSequence, Math.max(afterSequence, 0))
                        .orderByAsc(AgentRunEventEntity::getSequence)
                        .last("limit " + boundedLimit))
                .stream().map(this::snapshot).toList();
    }

    @Transactional(readOnly = true)
    public long lastSequence(Long runId) {
        Long sequence = events.selectLastSequence(runId);
        return sequence == null ? 0 : sequence;
    }

    /**
     * 订阅当前进程内该运行后续提交事件。调用方应先订阅再补读数据库，序号可消除竞态产生的重复。
     *
     * @param runId 运行 ID
     * @return 必须关闭的事件订阅
     */
    public EventSubscription subscribe(Long runId) {
        Objects.requireNonNull(runId, "runId");
        BlockingQueue<AgentEventSnapshot> queue = new LinkedBlockingQueue<>();
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(queue);
        return new EventSubscription(runId, queue);
    }

    private void publishAfterCommit(AgentEventSnapshot event) {
        Runnable publisher = () -> subscribers.getOrDefault(event.runId(), new CopyOnWriteArrayList<>())
                .forEach(queue -> queue.offer(event));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.run();
                }
            });
        } else {
            publisher.run();
        }
    }

    /** 当前进程内的提交后事件订阅；历史和重启恢复仍以数据库序号为准。 */
    public final class EventSubscription implements AutoCloseable {
        private final Long runId;
        private final BlockingQueue<AgentEventSnapshot> queue;
        private boolean closed;

        private EventSubscription(Long runId, BlockingQueue<AgentEventSnapshot> queue) {
            this.runId = runId;
            this.queue = queue;
        }

        /** @return 超时内到达的下一条已提交事件；没有事件时为 null */
        public AgentEventSnapshot poll(Duration timeout) throws InterruptedException {
            if (closed) {
                return null;
            }
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            var queues = subscribers.get(runId);
            if (queues != null) {
                queues.remove(queue);
                if (queues.isEmpty()) {
                    subscribers.remove(runId, queues);
                }
            }
        }
    }

    private AgentEventSnapshot snapshot(AgentRunEventEntity entity) {
        try {
            var root = objectMapper.readTree(entity.getPayload());
            String value = root.has("value") ? root.path("value").asText("") : root.toString();
            return new AgentEventSnapshot(
                    entity.getId(), entity.getRunId(), entity.getSequence(),
                    AgentEventType.valueOf(entity.getEventType()), entity.getSubjectType(), value, entity.getCreatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent event payload invalid", exception);
        }
    }

    private String json(String payload) {
        try {
            return objectMapper.writeValueAsString(Map.of("value", payload == null ? "" : payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("agent event payload invalid", exception);
        }
    }

    private String typedJson(AgentEvent.Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("agent typed event payload invalid", exception);
        }
    }

    private String legacySubject(AgentEventType type) {
        return switch (type) {
            case MODEL_STARTED, MODEL_STAGE -> AgentEvent.SubjectType.MODEL.name();
            case SOURCE_FOUND, SOURCE_DISCOVERED, TOOL_STARTED, TOOL_COMPLETED -> AgentEvent.SubjectType.TOOL.name();
            case CITATION_VALIDATION -> AgentEvent.SubjectType.VALIDATOR.name();
            default -> AgentEvent.SubjectType.AGENT.name();
        };
    }
}
