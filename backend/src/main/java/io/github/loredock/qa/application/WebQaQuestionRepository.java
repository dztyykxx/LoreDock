package io.github.loredock.qa.application;

import io.github.loredock.qa.domain.WebQaCursor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Web 问答身份仓储端口；所有可见性查询在数据库条件中限制操作者和项目。 */
public interface WebQaQuestionRepository {
    /** @return 已插入为 true，并发幂等冲突为 false */
    boolean insertIfAbsent(WebQaQuestionRecord question);

    /** @return 当前操作者与客户端键对应的原问答 */
    Optional<WebQaQuestionRecord> findByOperatorAndIdempotencyKey(String operatorId, String idempotencyKey);

    /** @return 同时匹配操作者、URL 项目与 ID 的问答，避免记录枚举 */
    Optional<WebQaQuestionRecord> findVisibleById(String operatorId, UUID projectId, UUID questionId);

    /** @return 按创建时间、稳定 ID 倒序且已限制操作者/项目的记录 */
    List<WebQaQuestionRecord> findHistory(String operatorId, UUID projectId, WebQaCursor after, int limit);
}
