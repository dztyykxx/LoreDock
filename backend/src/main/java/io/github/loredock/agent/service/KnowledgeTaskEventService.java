package io.github.loredock.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.agent.mapper.KnowledgeTaskEventMapper;
import io.github.loredock.agent.model.entity.KnowledgeTaskEventEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保存知识任务页面可续接的轻量事件游标；正文仍由任务快照按 subjectId 读取。 */
@Service
public class KnowledgeTaskEventService {

    private final KnowledgeTaskEventMapper events;

    public KnowledgeTaskEventService(KnowledgeTaskEventMapper events) {
        this.events = events;
    }

    @Transactional
    public long append(Long conversationId, Long runId, String type, Long subjectId, Instant occurredAt) {
        KnowledgeTaskEventEntity event = KnowledgeTaskEventEntity.builder()
                .conversationId(conversationId)
                .runId(runId)
                .eventType(type)
                .subjectId(subjectId)
                .occurredAt(occurredAt)
                .build();
        events.insert(event);
        return event.getId();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeTaskEventEntity> list(Long conversationId, long after, int limit) {
        if (conversationId == null || conversationId <= 0 || after < 0 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("知识任务事件游标无效");
        }
        return events.selectList(Wrappers.<KnowledgeTaskEventEntity>lambdaQuery()
                .eq(KnowledgeTaskEventEntity::getConversationId, conversationId)
                .gt(KnowledgeTaskEventEntity::getId, after)
                .orderByAsc(KnowledgeTaskEventEntity::getId)
                .last("limit " + limit));
    }

    @Transactional(readOnly = true)
    public long latest(Long conversationId) {
        KnowledgeTaskEventEntity event = events.selectOne(Wrappers.<KnowledgeTaskEventEntity>lambdaQuery()
                .eq(KnowledgeTaskEventEntity::getConversationId, conversationId)
                .orderByDesc(KnowledgeTaskEventEntity::getId)
                .last("limit 1"));
        return event == null ? 0 : event.getId();
    }
}
