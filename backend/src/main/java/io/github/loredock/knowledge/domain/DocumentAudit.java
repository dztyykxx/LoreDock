package io.github.loredock.knowledge.domain;

import java.time.Instant;

/**
 * 由应用层可信时间与操作者端口生成的领域审计输入。
 *
 * @param at UTC 时刻
 * @param actor 操作者稳定审计标识
 */
public record DocumentAudit(Instant at, String actor) {
    public DocumentAudit {
        if (at == null) {
            throw new IllegalArgumentException("document audit time is required");
        }
        actor = DocumentTextRules.normalizedRequired(actor, 255, "document audit actor");
    }
}
