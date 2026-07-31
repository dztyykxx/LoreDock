package io.github.loredock.qa.api;

/** Web 与 Feedback 使用的问答创建、历史和详情统一契约。 */
public interface QaService {

    /** @return 已受理或幂等复用的问答事实 */
    QaQuestion create(CreateRequest request);

    /** @return 当前操作者在指定项目中的有界问答历史 */
    QaQuestionPage history(HistoryQuery query);

    /** @return 当前操作者可见的问答详情 */
    QaQuestion detail(DetailQuery query);

    /**
     * @param operatorId 当前操作者
     * @param operatorRole ADMIN 或 MEMBER
     * @param idempotencyKey 操作者范围幂等键
     * @param projectIdentifier 项目标识
     * @param branch 可选分支
     * @param question 单次独立问题
     */
    record CreateRequest(
            String operatorId,
            String operatorRole,
            String idempotencyKey,
            String projectIdentifier,
            String branch,
            String question
    ) {
    }

    /** @param operatorId 当前操作者 @param projectIdentifier 项目标识 @param cursor 可选游标 @param limit 页大小 */
    record HistoryQuery(String operatorId, String projectIdentifier, String cursor, int limit) {
    }

    /** @param operatorId 当前操作者 @param projectIdentifier 项目标识 @param questionId 问答标识 */
    record DetailQuery(String operatorId, String projectIdentifier, Long questionId) {
    }
}
