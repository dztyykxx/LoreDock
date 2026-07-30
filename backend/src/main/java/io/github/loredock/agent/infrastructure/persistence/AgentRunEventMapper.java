package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.UUID;

/** 事件 Mapper；单次事务内锁定运行行并原子分配严格递增序号。 */
@Mapper
public interface AgentRunEventMapper extends BaseMapper<AgentRunEventEntity> {

    /**
     * 运行行的计数器更新在并发等待后会基于最新行版本递增；锁不跨模型或工具等待。
     */
    @Select("""
            with next_value as (
                update agent_run
                set event_sequence = event_sequence + 1
                where id = #{runId}
                returning event_sequence as sequence
            )
            insert into agent_run_event(id, run_id, sequence, event_type, payload, created_at)
            select #{eventId}, #{runId}, next_value.sequence, #{eventType}, cast(#{payload} as jsonb), #{createdAt}
            from next_value
            returning id, run_id as "runId", sequence, event_type as "eventType",
                      payload::text as payload, created_at as "createdAt"
            """)
    AgentRunEventEntity appendReturning(
            @Param("eventId") UUID eventId,
            @Param("runId") UUID runId,
            @Param("eventType") String eventType,
            @Param("payload") String payload,
            @Param("createdAt") Instant createdAt
    );
}
