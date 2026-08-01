package io.github.loredock.qa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.qa.mapper.WebQaConversationMapper;
import io.github.loredock.qa.model.entity.WebQaConversationEntity;
import io.github.loredock.qa.model.result.WebQaConversationRecord;
import io.github.loredock.qa.model.snapshot.WebQaCursor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** QA 会话 MyBatis-Plus 数据边界；范围条件在数据库查询中生效。 */
@Service
public class WebQaConversationDataService {
    private static final int MAX_QUERY_LIMIT = 101;
    private final WebQaConversationMapper mapper;

    /** @param mapper QA 会话 Mapper */
    public WebQaConversationDataService(WebQaConversationMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插入新会话并返回数据库分配的标识。
     *
     * @param conversation 会话归属、标题和时间事实
     * @return 带数据库标识的会话记录
     */
    public WebQaConversationRecord insert(WebQaConversationRecord conversation) {
        WebQaConversationEntity entity = toEntity(conversation);
        Long id = mapper.insertReturning(entity);
        return new WebQaConversationRecord(id, conversation.operatorId(), conversation.projectId(),
                conversation.projectIdentifier(), conversation.title(), conversation.createdAt(),
                conversation.updatedAt(), conversation.lastQuestionAt());
    }

    /**
     * 追加轮次前锁定当前操作者和项目可见的会话。
     *
     * @return 可见会话；越界或不存在时为空
     */
    public Optional<WebQaConversationRecord> lockVisible(Long id, String operatorId, Long projectId) {
        return Optional.ofNullable(mapper.selectVisibleForUpdate(id, operatorId, projectId)).map(this::toRecord);
    }

    /** @return 当前操作者和项目可见的会话；越界或不存在时为空 */
    public Optional<WebQaConversationRecord> findVisible(Long id, String operatorId, Long projectId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WebQaConversationEntity>lambdaQuery()
                        .eq(WebQaConversationEntity::getId, id)
                        .eq(WebQaConversationEntity::getOperatorId, operatorId)
                        .eq(WebQaConversationEntity::getProjectId, projectId)))
                .map(this::toRecord);
    }

    /**
     * 按最近问题时间和稳定标识倒序读取会话。
     *
     * @return 严格受操作者和项目范围限制的会话列表
     */
    public List<WebQaConversationRecord> findHistory(
            String operatorId,
            Long projectId,
            WebQaCursor after,
            int limit
    ) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new IllegalArgumentException("web QA conversation history limit out of range");
        }
        LambdaQueryWrapper<WebQaConversationEntity> query = Wrappers.<WebQaConversationEntity>lambdaQuery()
                .eq(WebQaConversationEntity::getOperatorId, operatorId)
                .eq(WebQaConversationEntity::getProjectId, projectId);
        if (after != null) {
            query.and(bounds -> bounds.lt(WebQaConversationEntity::getLastQuestionAt, after.createdAt())
                    .or(sameTime -> sameTime.eq(WebQaConversationEntity::getLastQuestionAt, after.createdAt())
                            .lt(WebQaConversationEntity::getId, after.id())));
        }
        query.orderByDesc(WebQaConversationEntity::getLastQuestionAt)
                .orderByDesc(WebQaConversationEntity::getId)
                .last("limit " + limit);
        return mapper.selectList(query).stream().map(this::toRecord).toList();
    }

    /**
     * 在新轮次成功创建后推进会话活动时间。
     *
     * @param id 会话标识
     * @param updatedAt 新轮次创建时间
     */
    public void updateActivity(Long id, Instant updatedAt) {
        WebQaConversationEntity value = new WebQaConversationEntity();
        value.setId(id);
        value.setUpdatedAt(updatedAt);
        value.setLastQuestionAt(updatedAt);
        if (mapper.updateById(value) != 1) {
            throw new IllegalStateException("QA conversation activity update failed");
        }
    }

    /**
     * 删除幂等竞争输家刚创建但未绑定任何问题的会话。
     *
     * @param id 输家会话标识
     */
    public void deleteEmpty(Long id) {
        if (mapper.deleteEmpty(id) != 1) {
            throw new IllegalStateException("empty QA conversation cleanup failed");
        }
    }

    private WebQaConversationEntity toEntity(WebQaConversationRecord value) {
        return WebQaConversationEntity.builder()
                .id(value.id()).operatorId(value.operatorId()).projectId(value.projectId())
                .projectIdentifier(value.projectIdentifier()).title(value.title())
                .createdAt(value.createdAt()).updatedAt(value.updatedAt())
                .lastQuestionAt(value.lastQuestionAt()).build();
    }

    private WebQaConversationRecord toRecord(WebQaConversationEntity value) {
        return new WebQaConversationRecord(value.getId(), value.getOperatorId(), value.getProjectId(),
                value.getProjectIdentifier(), value.getTitle(), value.getCreatedAt(), value.getUpdatedAt(),
                value.getLastQuestionAt());
    }
}
