package io.github.loredock.knowledgegap.application;

import io.github.loredock.knowledgegap.domain.KnowledgeGapCursor;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 知识缺口持久化端口；列表过滤和状态比较更新必须在数据库内完成。 */
public interface KnowledgeGapFeedbackRepository {
    /** @return 新记录已插入为 true，并发相同操作者/幂等键冲突为 false */
    boolean insertIfAbsent(KnowledgeGapFeedbackRecord feedback);

    /** 保存与新反馈同事务的有限证据关联。 */
    void insertCitations(List<KnowledgeGapCitationRecord> citations);

    /** @return 当前操作者与客户端键对应的原反馈 */
    Optional<KnowledgeGapFeedbackRecord> findByOperatorAndIdempotencyKey(String operatorId, String key);

    /** @return 管理端按 ID 查询的反馈 */
    Optional<KnowledgeGapFeedbackRecord> findById(UUID feedbackId);

    /** @return 已在数据库应用过滤和复合游标的反馈 */
    List<KnowledgeGapFeedbackRecord> findAll(KnowledgeGapFilter filter, KnowledgeGapCursor after, int limit);

    /** @return 按原引用顺序排列的有限证据关联 */
    List<KnowledgeGapCitationRecord> findCitations(UUID feedbackId);

    /** @return 当前状态等于 expected 且成功更新时为 true */
    boolean updateStatus(
            UUID feedbackId, KnowledgeGapStatus expected, KnowledgeGapStatus target,
            String actor, Instant updatedAt);
}
