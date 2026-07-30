package io.github.loredock.qa.application;

import java.util.List;
import java.util.UUID;

/** Web 问答消息仓储端口；数据库唯一角色保证终态投影可幂等自愈。 */
public interface WebQaMessageRepository {
    /** @return 已插入为 true，同一问答角色已存在为 false */
    boolean insertIfAbsent(WebQaMessageRecord message);

    /** @return 用户消息在前、助手消息在后的稳定消息集合 */
    List<WebQaMessageRecord> findByQuestionId(UUID questionId);
}
