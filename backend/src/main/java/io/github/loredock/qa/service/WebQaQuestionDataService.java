package io.github.loredock.qa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.qa.mapper.WebQaQuestionMapper;
import io.github.loredock.qa.model.entity.WebQaQuestionEntity;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.snapshot.WebQaCursor;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * MyBatis-Plus 问答仓储适配器；历史与详情在 SQL 条件中强制操作者和项目范围，禁止跨范围加载后隐藏。
 */
@Service
public class WebQaQuestionDataService {
    private static final int MAX_QUERY_LIMIT = 101;
    private final WebQaQuestionMapper mapper;

    /** @param mapper 问答身份 Mapper */
    public WebQaQuestionDataService(WebQaQuestionMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Long> insertIfAbsent(WebQaQuestionRecord question) {
        return Optional.ofNullable(mapper.insertIfAbsent(toEntity(question)));
    }

    public Optional<WebQaQuestionRecord> findByOperatorAndIdempotencyKey(
            String operatorId,
            String idempotencyKey
    ) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WebQaQuestionEntity>lambdaQuery()
                        .eq(WebQaQuestionEntity::getOperatorId, operatorId)
                        .eq(WebQaQuestionEntity::getIdempotencyKey, idempotencyKey)))
                .map(this::toRecord);
    }

    public Optional<WebQaQuestionRecord> findVisibleById(String operatorId, Long projectId, Long questionId) {
        LambdaQueryWrapper<WebQaQuestionEntity> query = Wrappers.<WebQaQuestionEntity>lambdaQuery()
                .eq(WebQaQuestionEntity::getId, questionId)
                .eq(WebQaQuestionEntity::getOperatorId, operatorId);
        // 全局轮次 project_id 为空，与项目轮次互斥：范围条件必须区分 NULL 与具体项目。
        if (projectId == null) {
            query.isNull(WebQaQuestionEntity::getProjectId);
        } else {
            query.eq(WebQaQuestionEntity::getProjectId, projectId);
        }
        return Optional.ofNullable(mapper.selectOne(query)).map(this::toRecord);
    }

    public List<WebQaQuestionRecord> findHistory(
            String operatorId,
            Long projectId,
            WebQaCursor after,
            int limit
    ) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new IllegalArgumentException("web QA history limit out of range");
        }
        LambdaQueryWrapper<WebQaQuestionEntity> query = Wrappers.<WebQaQuestionEntity>lambdaQuery()
                .eq(WebQaQuestionEntity::getOperatorId, operatorId);
        // 全局历史只读 project_id 为空的轮次；项目历史按具体项目限定，两者互斥。
        if (projectId == null) {
            query.isNull(WebQaQuestionEntity::getProjectId);
        } else {
            query.eq(WebQaQuestionEntity::getProjectId, projectId);
        }
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

    /** @return 会话中按创建时间和稳定 ID 正序排列的有界轮次 */
    public List<WebQaQuestionRecord> findByConversation(Long conversationId, int limit) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new IllegalArgumentException("web QA conversation round limit out of range");
        }
        return mapper.selectList(Wrappers.<WebQaQuestionEntity>lambdaQuery()
                        .eq(WebQaQuestionEntity::getConversationId, conversationId)
                        .orderByAsc(WebQaQuestionEntity::getCreatedAt)
                        .orderByAsc(WebQaQuestionEntity::getId)
                        .last("limit " + limit))
                .stream().map(this::toRecord).toList();
    }

    /** @return 会话是否仍有 ACCEPTED 或 RUNNING 轮次 */
    public boolean hasActiveRound(Long conversationId) {
        return mapper.countActiveByConversation(conversationId) > 0;
    }

    private WebQaQuestionEntity toEntity(WebQaQuestionRecord value) {
        return WebQaQuestionEntity.builder()
                .id(value.id()).conversationId(value.conversationId()).operatorId(value.operatorId())
                .idempotencyKey(value.idempotencyKey())
                .requestHash(value.requestHash()).projectId(value.projectId())
                .projectIdentifier(value.projectIdentifier()).branchId(value.branchId()).branchName(value.branch())
                .runId(value.runId()).createdAt(value.createdAt()).build();
    }

    private WebQaQuestionRecord toRecord(WebQaQuestionEntity value) {
        return new WebQaQuestionRecord(
                value.getId(), value.getConversationId(), value.getOperatorId(), value.getIdempotencyKey(), value.getRequestHash(),
                value.getProjectId(), value.getProjectIdentifier(), value.getBranchId(), value.getBranchName(),
                value.getRunId(), value.getCreatedAt());
    }
}
