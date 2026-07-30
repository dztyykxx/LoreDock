package io.github.loredock.qa.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 基于创建时间和稳定 ID 的倒序分页位置。 */
public record WebQaCursor(Instant createdAt, UUID id) {
    public WebQaCursor {
        Objects.requireNonNull(createdAt, "cursor createdAt");
        Objects.requireNonNull(id, "cursor id");
    }
}
