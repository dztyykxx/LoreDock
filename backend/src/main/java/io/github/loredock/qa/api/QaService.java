package io.github.loredock.qa.api;

import java.time.Instant;
import java.util.List;

/** Web 与 Feedback 使用的问答创建、历史和详情统一契约。 */
public interface QaService {

    /** @return 已受理或幂等复用的问答事实 */
    QaQuestion create(CreateRequest request);

    /** @return 当前操作者在指定项目中的有界问答历史 */
    QaQuestionPage history(HistoryQuery query);

    /** @return 当前操作者可见的问答详情 */
    QaQuestion detail(DetailQuery query);

    /** @return 当前操作者在指定项目中的有界最近会话 */
    ConversationPage conversations(ConversationHistoryQuery query);

    /** @return 当前操作者可见的会话及其稳定顺序轮次 */
    Conversation conversation(ConversationDetailQuery query);

    /** @return 已受理或幂等复用的全局（全库）问答事实 */
    QaQuestion createGlobal(GlobalCreateRequest request);

    /** @return 当前操作者在全局（全库）范围内的有界问答历史 */
    QaQuestionPage historyGlobal(GlobalHistoryQuery query);

    /** @return 当前操作者在全部范围中的有界最近会话；GLOBAL 与会话归属项目混排 */
    ConversationPage conversationsGlobal(GlobalConversationHistoryQuery query);

    /** @return 当前操作者可见的全局会话及其稳定顺序轮次 */
    Conversation conversationGlobal(GlobalConversationDetailQuery query);

    /** @return 当前操作者可见的全局问答详情 */
    QaQuestion detailGlobal(GlobalDetailQuery query);

    /**
     * @param operatorId 当前操作者
     * @param operatorRole ADMIN 或 MEMBER
     * @param idempotencyKey 操作者范围幂等键
     * @param projectIdentifier 项目标识
     * @param branch 可选分支
     * @param conversationId 可选既有会话；省略时创建新会话
     * @param question 当前轮次的独立问题
     */
    record CreateRequest(
            String operatorId,
            String operatorRole,
            String idempotencyKey,
            String projectIdentifier,
            String branch,
            Long conversationId,
            String question
    ) {
        /** 保留旧调用方省略会话字段时创建新会话的兼容语义。 */
        public CreateRequest(
                String operatorId,
                String operatorRole,
                String idempotencyKey,
                String projectIdentifier,
                String branch,
                String question
        ) {
            this(operatorId, operatorRole, idempotencyKey, projectIdentifier, branch, null, question);
        }
    }

    /** @param operatorId 当前操作者 @param projectIdentifier 项目标识 @param cursor 可选游标 @param limit 页大小 */
    record HistoryQuery(String operatorId, String projectIdentifier, String cursor, int limit) {
    }

    /** @param operatorId 当前操作者 @param projectIdentifier 项目标识 @param questionId 问答标识 */
    record DetailQuery(String operatorId, String projectIdentifier, Long questionId) {
    }

    /** @param operatorId 当前操作者 @param projectIdentifier 项目标识 @param cursor 可选游标 @param limit 页大小 */
    record ConversationHistoryQuery(String operatorId, String projectIdentifier, String cursor, int limit) {
    }

    /** @param operatorId 当前操作者 @param projectIdentifier 项目标识 @param conversationId 会话标识 */
    record ConversationDetailQuery(String operatorId, String projectIdentifier, Long conversationId) {
    }

    /**
     * @param operatorId 当前操作者
     * @param idempotencyKey 操作者范围幂等键
     * @param conversationId 可选既有全局会话；省略时创建新会话
     * @param question 当前轮次的独立问题
     */
    record GlobalCreateRequest(
            String operatorId,
            String operatorRole,
            String idempotencyKey,
            Long conversationId,
            String question
    ) {
    }

    /** @param operatorId 当前操作者 @param cursor 可选游标 @param limit 页大小 */
    record GlobalHistoryQuery(String operatorId, String cursor, int limit) {
    }

    /** @param operatorId 当前操作者 @param cursor 可选游标 @param limit 页大小 */
    record GlobalConversationHistoryQuery(String operatorId, String cursor, int limit) {
    }

    /** @param operatorId 当前操作者 @param conversationId 会话标识 */
    record GlobalConversationDetailQuery(String operatorId, Long conversationId) {
    }

    /** @param operatorId 当前操作者 @param questionId 问答标识 */
    record GlobalDetailQuery(String operatorId, Long questionId) {
    }

    /**
     * 当前操作者可见的会话摘要；标题只来自首轮用户问题的安全截断值。
     *
     * @param conversationId 会话标识
     * @param projectIdentifier 固定项目标识；GLOBAL 会话为哨兵 "GLOBAL"
     * @param projectName 项目显示名；GLOBAL 会话为空
     * @param scope GLOBAL 或 PROJECT；由项目归属推导，不入库
     * @param title 会话标题摘要
     * @param lastQuestion 最后一轮用户问题摘要
     * @param status 最后一轮运行状态
     * @param createdAt 创建时间
     * @param updatedAt 最近更新时间
     * @param lastQuestionAt 最后一轮问题时间
     */
    record ConversationSummary(
            Long conversationId,
            String projectIdentifier,
            String projectName,
            String scope,
            String title,
            String lastQuestion,
            QaQuestion.Status status,
            Instant createdAt,
            Instant updatedAt,
            Instant lastQuestionAt
    ) {
    }

    /** @param items 当前页会话 @param nextCursor 下一页游标 */
    record ConversationPage(List<ConversationSummary> items, String nextCursor) {
        public ConversationPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 会话详情只包含稳定终态或明确运行状态的独立轮次，不合并运行事件流。
     *
     * @param summary 会话摘要
     * @param rounds 按创建时间和稳定 ID 正序排列的有界轮次
     */
    record Conversation(ConversationSummary summary, List<QaQuestion> rounds) {
        public Conversation {
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
        }
    }
}
