package io.github.loredock.knowledgegap.application;

import java.util.UUID;

/** 只由 `/api/admin/**` 入口调用的最小反馈管理能力。 */
public interface AdminKnowledgeGapUseCase {
    /** @return 数据库内过滤后的有界游标页 */
    KnowledgeGapFeedbackPage list(QueryKnowledgeGapsCommand command);

    /** @return 指定反馈的有限详情；不存在时抛出稳定 404 */
    KnowledgeGapFeedbackSnapshot detail(UUID feedbackId);

    /** @return 幂等保持或单向推进后的反馈详情 */
    KnowledgeGapFeedbackSnapshot updateStatus(UpdateKnowledgeGapStatusCommand command);
}
