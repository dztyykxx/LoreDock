package io.github.loredock.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AuditMetadataFactoryTest {

    /**
     * 业务目的：认证接入前创建记录也必须留下明确 SYSTEM 身份和 UTC 时刻，防止审计字段为空或伪造用户。
     */
    @Test
    void createAuditMetadataUsesCurrentTimeAndSystemActor() {
        Instant now = Instant.parse("2026-07-29T12:00:00Z");
        AuditMetadataFactory factory = new AuditMetadataFactory(
                Clock.fixed(now, java.time.ZoneOffset.UTC), () -> "SYSTEM");

        AuditMetadata metadata = factory.created();

        assertThat(metadata.createdAt()).isEqualTo(now);
        assertThat(metadata.updatedAt()).isEqualTo(now);
        assertThat(metadata.createdBy()).isEqualTo("SYSTEM");
        assertThat(metadata.updatedBy()).isEqualTo("SYSTEM");
    }

    /**
     * 业务目的：更新记录必须保留创建证据并刷新更新证据，防止一次修改覆盖原始创建者和创建时间。
     */
    @Test
    void updateAuditMetadataPreservesCreationAndRefreshesUpdate() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-29T12:00:00Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        AuditMetadataFactory factory = new AuditMetadataFactory(clock, () -> "SYSTEM");
        AuditMetadata original = factory.created();
        now.set(Instant.parse("2026-07-29T13:00:00Z"));

        AuditMetadata updated = factory.updated(original);

        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
        assertThat(updated.createdBy()).isEqualTo(original.createdBy());
        assertThat(updated.updatedAt()).isEqualTo(now.get());
        assertThat(updated.updatedBy()).isEqualTo("SYSTEM");
    }
}
