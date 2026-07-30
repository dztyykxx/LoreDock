package io.github.loredock.qa.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.qa.application.WebQaMessageRecord;
import io.github.loredock.qa.application.WebQaMessageRepository;
import io.github.loredock.qa.domain.WebQaMessageRole;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** MyBatis-Plus 问答消息仓储适配器，以数据库唯一角色实现终态消息幂等投影。 */
@Repository
public class MybatisPlusWebQaMessageRepository implements WebQaMessageRepository {
    private final WebQaMessageMapper mapper;

    /** @param mapper 问答消息 Mapper */
    public MybatisPlusWebQaMessageRepository(WebQaMessageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfAbsent(WebQaMessageRecord message) {
        return mapper.insertIfAbsent(toEntity(message)) == 1;
    }

    @Override
    public List<WebQaMessageRecord> findByQuestionId(UUID questionId) {
        return mapper.selectList(Wrappers.<WebQaMessageEntity>lambdaQuery()
                        .eq(WebQaMessageEntity::getQuestionId, questionId)
                        .orderByAsc(WebQaMessageEntity::getCreatedAt)
                        .orderByAsc(WebQaMessageEntity::getId))
                .stream().map(this::toRecord).toList();
    }

    private WebQaMessageEntity toEntity(WebQaMessageRecord value) {
        return WebQaMessageEntity.builder()
                .id(value.id()).questionId(value.questionId()).role(value.role().name()).content(value.content())
                .resultType(value.resultType() == null ? null : value.resultType().name())
                .refusalReason(value.refusalReason() == null ? null : value.refusalReason().name())
                .createdAt(value.createdAt()).build();
    }

    private WebQaMessageRecord toRecord(WebQaMessageEntity value) {
        return new WebQaMessageRecord(
                value.getId(), value.getQuestionId(), WebQaMessageRole.valueOf(value.getRole()), value.getContent(),
                enumValue(AgentResultType.class, value.getResultType()),
                enumValue(AgentRefusalReason.class, value.getRefusalReason()), value.getCreatedAt());
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
