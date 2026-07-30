package io.github.loredock.knowledgegap.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.knowledgegap.application.KnowledgeGapCitationRecord;
import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackRecord;
import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackRepository;
import io.github.loredock.knowledgegap.application.KnowledgeGapFilter;
import io.github.loredock.knowledgegap.domain.KnowledgeGapCursor;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MyBatis-Plus 知识缺口仓储；复合游标和比较更新均在 PostgreSQL 条件内执行。 */
@Repository
public class MybatisPlusKnowledgeGapFeedbackRepository implements KnowledgeGapFeedbackRepository {
    private final KnowledgeGapFeedbackMapper feedback;
    private final KnowledgeGapFeedbackCitationMapper citations;

    /** @param feedback 反馈 Mapper @param citations 引用关联 Mapper */
    public MybatisPlusKnowledgeGapFeedbackRepository(
            KnowledgeGapFeedbackMapper feedback,
            KnowledgeGapFeedbackCitationMapper citations
    ) {
        this.feedback = feedback;
        this.citations = citations;
    }

    @Override
    @Transactional
    public boolean insertIfAbsent(KnowledgeGapFeedbackRecord record) {
        return feedback.insertIfAbsent(toEntity(record)) == 1;
    }

    @Override
    @Transactional
    public void insertCitations(List<KnowledgeGapCitationRecord> values) {
        for (KnowledgeGapCitationRecord value : values) {
            citations.insert(KnowledgeGapFeedbackCitationEntity.builder()
                    .id(value.id()).feedbackId(value.feedbackId()).runId(value.runId())
                    .evidenceId(value.evidenceId()).citationOrder(value.order()).createdAt(value.createdAt())
                    .build());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeGapFeedbackRecord> findByOperatorAndIdempotencyKey(String operatorId, String key) {
        return Optional.ofNullable(feedback.selectOne(new LambdaQueryWrapper<KnowledgeGapFeedbackEntity>()
                        .eq(KnowledgeGapFeedbackEntity::getOperatorId, operatorId)
                        .eq(KnowledgeGapFeedbackEntity::getIdempotencyKey, key)))
                .map(this::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeGapFeedbackRecord> findById(UUID feedbackId) {
        return Optional.ofNullable(feedback.selectById(feedbackId)).map(this::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeGapFeedbackRecord> findAll(
            KnowledgeGapFilter filter,
            KnowledgeGapCursor after,
            int limit
    ) {
        LambdaQueryWrapper<KnowledgeGapFeedbackEntity> query = new LambdaQueryWrapper<>();
        query.eq(filter.projectIdentifier() != null,
                        KnowledgeGapFeedbackEntity::getProjectIdentifier, filter.projectIdentifier())
                .eq(filter.branch() != null, KnowledgeGapFeedbackEntity::getBranchName, filter.branch())
                .eq(filter.type() != null, KnowledgeGapFeedbackEntity::getGapType,
                        filter.type() == null ? null : filter.type().name())
                .eq(filter.status() != null, KnowledgeGapFeedbackEntity::getStatus,
                        filter.status() == null ? null : filter.status().name());
        if (after != null) {
            query.and(outer -> outer.lt(KnowledgeGapFeedbackEntity::getCreatedAt, after.createdAt())
                    .or(sameTime -> sameTime.eq(KnowledgeGapFeedbackEntity::getCreatedAt, after.createdAt())
                            .lt(KnowledgeGapFeedbackEntity::getId, after.id())));
        }
        query.orderByDesc(KnowledgeGapFeedbackEntity::getCreatedAt)
                .orderByDesc(KnowledgeGapFeedbackEntity::getId)
                .last("limit " + limit);
        return feedback.selectList(query).stream().map(this::toRecord).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeGapCitationRecord> findCitations(UUID feedbackId) {
        return citations.selectList(new LambdaQueryWrapper<KnowledgeGapFeedbackCitationEntity>()
                        .eq(KnowledgeGapFeedbackCitationEntity::getFeedbackId, feedbackId)
                        .orderByAsc(KnowledgeGapFeedbackCitationEntity::getCitationOrder))
                .stream().map(entity -> new KnowledgeGapCitationRecord(
                        entity.getId(), entity.getFeedbackId(), entity.getRunId(), entity.getEvidenceId(),
                        entity.getCitationOrder(), entity.getCreatedAt())).toList();
    }

    @Override
    @Transactional
    public boolean updateStatus(
            UUID feedbackId,
            KnowledgeGapStatus expected,
            KnowledgeGapStatus target,
            String actor,
            Instant updatedAt
    ) {
        return feedback.updateStatus(
                feedbackId, expected.name(), target.name(), actor, updatedAt) == 1;
    }

    private KnowledgeGapFeedbackEntity toEntity(KnowledgeGapFeedbackRecord value) {
        return KnowledgeGapFeedbackEntity.builder()
                .id(value.id()).operatorId(value.operatorId()).idempotencyKey(value.idempotencyKey())
                .requestHash(value.requestHash()).projectId(value.projectId())
                .projectIdentifier(value.projectIdentifier()).branchId(value.branchId()).branchName(value.branch())
                .questionId(value.questionId()).runId(value.runId()).gapType(value.type().name())
                .status(value.status().name()).questionText(value.question()).note(value.note())
                .resultType(name(value.resultType())).refusalReason(name(value.refusalReason()))
                .errorCode(name(value.errorCode())).createdAt(value.createdAt()).updatedAt(value.updatedAt())
                .createdBy(value.createdBy()).updatedBy(value.updatedBy()).build();
    }

    private KnowledgeGapFeedbackRecord toRecord(KnowledgeGapFeedbackEntity value) {
        return new KnowledgeGapFeedbackRecord(
                value.getId(), value.getOperatorId(), value.getIdempotencyKey(), value.getRequestHash(),
                value.getProjectId(), value.getProjectIdentifier(), value.getBranchId(), value.getBranchName(),
                value.getQuestionId(), value.getRunId(), KnowledgeGapType.valueOf(value.getGapType()),
                KnowledgeGapStatus.valueOf(value.getStatus()), value.getQuestionText(), value.getNote(),
                enumValue(AgentResultType.class, value.getResultType()),
                enumValue(AgentRefusalReason.class, value.getRefusalReason()),
                enumValue(AgentErrorCode.class, value.getErrorCode()), value.getCreatedAt(), value.getUpdatedAt(),
                value.getCreatedBy(), value.getUpdatedBy());
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
