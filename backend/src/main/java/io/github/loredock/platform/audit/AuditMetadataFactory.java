package io.github.loredock.platform.audit;

import io.github.loredock.platform.time.TimeProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * 统一创建和更新审计值，保证创建证据不会在修改记录时被覆盖。
 */
@Component
public class AuditMetadataFactory {

    private final TimeProvider timeProvider;
    private final ActorProvider actorProvider;

    /**
     * @param timeProvider 可替换 UTC 时间端口
     * @param actorProvider 当前操作者端口
     */
    public AuditMetadataFactory(TimeProvider timeProvider, ActorProvider actorProvider) {
        this.timeProvider = timeProvider;
        this.actorProvider = actorProvider;
    }

    /**
     * 创建首次持久化所需的完整审计值。
     *
     * @return 创建与更新时间、操作者均已填充的审计值
     */
    public AuditMetadata created() {
        Instant now = timeProvider.now();
        String actor = actorProvider.currentActor();
        return new AuditMetadata(now, now, actor, actor);
    }

    /**
     * 保留创建证据并刷新本次修改证据。
     *
     * @param original 既有审计值
     * @return 更新后的审计值
     */
    public AuditMetadata updated(AuditMetadata original) {
        Objects.requireNonNull(original, "原审计信息不能为空");
        return new AuditMetadata(
                original.createdAt(),
                timeProvider.now(),
                original.createdBy(),
                actorProvider.currentActor()
        );
    }
}
