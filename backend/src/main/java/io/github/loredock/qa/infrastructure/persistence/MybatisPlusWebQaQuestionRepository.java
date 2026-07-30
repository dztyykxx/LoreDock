package io.github.loredock.qa.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.qa.application.WebQaQuestionRecord;
import io.github.loredock.qa.application.WebQaQuestionRepository;
import io.github.loredock.qa.domain.WebQaCursor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatis-Plus 问答仓储适配器；历史与详情在 SQL 条件中强制操作者和项目范围，禁止跨范围加载后隐藏。
 */
@Repository
public class MybatisPlusWebQaQuestionRepository implements WebQaQuestionRepository {
    private static final int MAX_QUERY_LIMIT = 101;
    private final WebQaQuestionMapper mapper;

    /** @param mapper 问答身份 Mapper */
    public MybatisPlusWebQaQuestionRepository(WebQaQuestionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfAbsent(WebQaQuestionRecord question) {
        return mapper.insertIfAbsent(toEntity(question)) == 1;
    }

    @Override
    public Optional<WebQaQuestionRecord> findByOperatorAndIdempotencyKey(
            String operatorId,
            String idempotencyKey
    ) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WebQaQuestionEntity>lambdaQuery()
                        .eq(WebQaQuestionEntity::getOperatorId, operatorId)
                        .eq(WebQaQuestionEntity::getIdempotencyKey, idempotencyKey)))
                .map(this::toRecord);
    }

    @Override
    public Optional<WebQaQuestionRecord> findVisibleById(String operatorId, UUID projectId, UUID questionId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WebQaQuestionEntity>lambdaQuery()
                        .eq(WebQaQuestionEntity::getId, questionId)
                        .eq(WebQaQuestionEntity::getOperatorId, operatorId)
                        .eq(WebQaQuestionEntity::getProjectId, projectId)))
                .map(this::toRecord);
    }

    @Override
    public List<WebQaQuestionRecord> findHistory(
            String operatorId,
            UUID projectId,
            WebQaCursor after,
            int limit
    ) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new IllegalArgumentException("web QA history limit out of range");
        }
        LambdaQueryWrapper<WebQaQuestionEntity> query = Wrappers.<WebQaQuestionEntity>lambdaQuery()
                .eq(WebQaQuestionEntity::getOperatorId, operatorId)
                .eq(WebQaQuestionEntity::getProjectId, projectId);
        if (after != null) {
            query.and(bounds -> bounds
                    .lt(WebQaQuestionEntity::getCreatedAt, after.createdAt())
                    .or(sameTime -> sameTime
                            .eq(WebQaQuestionEntity::getCreatedAt, after.createdAt())
                            .lt(WebQaQuestionEntity::getId, after.id())));
        }
        query.orderByDesc(WebQaQuestionEntity::getCreatedAt)
                .orderByDesc(WebQaQuestionEntity::getId)
                .last("limit " + limit);
        return mapper.selectList(query).stream().map(this::toRecord).toList();
    }

    private WebQaQuestionEntity toEntity(WebQaQuestionRecord value) {
        return WebQaQuestionEntity.builder()
                .id(value.id()).operatorId(value.operatorId()).idempotencyKey(value.idempotencyKey())
                .requestHash(value.requestHash()).projectId(value.projectId())
                .projectIdentifier(value.projectIdentifier()).branchId(value.branchId()).branchName(value.branch())
                .runId(value.runId()).createdAt(value.createdAt()).build();
    }

    private WebQaQuestionRecord toRecord(WebQaQuestionEntity value) {
        return new WebQaQuestionRecord(
                value.getId(), value.getOperatorId(), value.getIdempotencyKey(), value.getRequestHash(),
                value.getProjectId(), value.getProjectIdentifier(), value.getBranchId(), value.getBranchName(),
                value.getRunId(), value.getCreatedAt());
    }
}
