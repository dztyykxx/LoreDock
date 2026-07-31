package io.github.loredock.qa.model.snapshot;

import java.time.Instant;
import java.util.Objects;

/** 基于创建时间和稳定 ID 的倒序分页位置。 */
public record WebQaCursor(Instant createdAt, Long id) {
    public WebQaCursor {
        Objects.requireNonNull(createdAt, "cursor createdAt");
        Objects.requireNonNull(id, "cursor id");
    }
}
