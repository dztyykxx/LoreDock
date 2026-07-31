package io.github.loredock.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.auth.api.AuthenticatedActor;
import io.github.loredock.auth.api.WebRole;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthenticatedActorServiceTest {

    /**
     * 业务目的：管理员 Web 请求产生的写审计必须记录稳定账号标识，不能继续全部记成 SYSTEM。
     */
    @Test
    void authenticatedAdminIsRecordedAsAuditActor() {
        SessionService sessions = mock(SessionService.class);
        when(sessions.current()).thenReturn(Optional.of(new AuthenticatedActor("admin", "管理员", WebRole.ADMIN)));
        AuthenticatedActorService actorProvider = new AuthenticatedActorService(sessions);
        AuditMetadataFactory factory = new AuditMetadataFactory(
                Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), java.time.ZoneOffset.UTC), actorProvider);

        AuditMetadata metadata = factory.created();

        assertThat(metadata.createdBy()).isEqualTo("admin");
        assertThat(metadata.updatedBy()).isEqualTo("admin");
    }

    /**
     * 业务目的：后台、启动恢复和其他没有 Web 身份的机器工作必须保留 SYSTEM，不能冒充最近登录用户。
     */
    @Test
    void missingWebIdentityUsesSystemActor() {
        SessionService sessions = mock(SessionService.class);
        when(sessions.current()).thenReturn(Optional.empty());
        AuthenticatedActorService actorProvider = new AuthenticatedActorService(sessions);

        assertThat(actorProvider.currentActor()).isEqualTo("SYSTEM");
    }

    /**
     * 业务目的：请求会话清理后下一项机器工作必须立即回到 SYSTEM，防止线程复用泄露上一个管理员身份。
     */
    @Test
    void clearedRequestIdentityDoesNotLeakIntoFollowingWork() {
        SessionService sessions = mock(SessionService.class);
        when(sessions.current()).thenReturn(
                Optional.of(new AuthenticatedActor("admin", "管理员", WebRole.ADMIN)),
                Optional.empty());
        AuthenticatedActorService actorProvider = new AuthenticatedActorService(sessions);
        assertThat(actorProvider.currentActor()).isEqualTo("admin");

        assertThat(actorProvider.currentActor()).isEqualTo("SYSTEM");
    }
}
