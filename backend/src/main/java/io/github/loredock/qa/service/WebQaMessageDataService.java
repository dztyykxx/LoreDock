package io.github.loredock.qa.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.qa.mapper.WebQaMessageMapper;
import io.github.loredock.qa.model.entity.WebQaMessageEntity;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** MyBatis-Plus 问答消息仓储适配器，以数据库唯一角色实现终态消息幂等投影。 */
@Service
public class WebQaMessageDataService {
    private final WebQaMessageMapper mapper;

    /** @param mapper 问答消息 Mapper */
    public WebQaMessageDataService(WebQaMessageMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Long> insertIfAbsent(WebQaMessageRecord message) {
        return Optional.ofNullable(mapper.insertIfAbsent(toEntity(message)));
    }

    public List<WebQaMessageRecord> findByQuestionId(Long questionId) {
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
                enumValue(AgentRun.ResultType.class, value.getResultType()),
                enumValue(AgentRun.RefusalReason.class, value.getRefusalReason()), value.getCreatedAt());
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
