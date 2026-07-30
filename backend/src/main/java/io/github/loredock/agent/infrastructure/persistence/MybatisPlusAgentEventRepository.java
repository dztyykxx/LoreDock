package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.application.AgentEventRepository;
import io.github.loredock.agent.application.AgentEventSnapshot;
import io.github.loredock.agent.domain.AgentEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MyBatis-Plus 事件仓储，每次 append 是独立短事务并在提交后才可被查询。 */
@Repository
@Slf4j
public class MybatisPlusAgentEventRepository implements AgentEventRepository {

    private final AgentRunEventMapper events;
    private final ObjectMapper objectMapper;

    /** @param events 事件 Mapper @param objectMapper JSON 序列化器 */
    public MybatisPlusAgentEventRepository(AgentRunEventMapper events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AgentEventSnapshot append(UUID runId, AgentEventType type, String safePayload, Instant createdAt) {
        AgentRunEventEntity entity = events.appendReturning(
                UUID.randomUUID(), runId, type.name(), json(safePayload), createdAt);
        log.debug("agent_event persisted runId={} sequence={} eventType={} payloadLength={}",
                runId, entity.getSequence(), type, safePayload == null ? 0 : safePayload.length());
        return snapshot(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentEventSnapshot> findAfter(UUID runId, long afterSequence, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 200);
        return events.selectList(new LambdaQueryWrapper<AgentRunEventEntity>()
                        .eq(AgentRunEventEntity::getRunId, runId)
                        .gt(AgentRunEventEntity::getSequence, Math.max(afterSequence, 0))
                        .orderByAsc(AgentRunEventEntity::getSequence)
                        .last("limit " + boundedLimit))
                .stream().map(this::snapshot).toList();
    }

    private AgentEventSnapshot snapshot(AgentRunEventEntity entity) {
        try {
            String value = objectMapper.readTree(entity.getPayload()).path("value").asText("");
            return new AgentEventSnapshot(
                    entity.getId(), entity.getRunId(), entity.getSequence(),
                    AgentEventType.valueOf(entity.getEventType()), value, entity.getCreatedAt());
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
}
