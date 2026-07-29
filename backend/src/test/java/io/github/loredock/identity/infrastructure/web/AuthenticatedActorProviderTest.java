package io.github.loredock.identity.infrastructure.web;

import io.github.loredock.identity.application.WebSessionPort;
import io.github.loredock.identity.domain.AuthenticatedActor;
import io.github.loredock.identity.domain.WebRole;
import io.github.loredock.platform.audit.AuditMetadata;
import io.github.loredock.platform.audit.AuditMetadataFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedActorProviderTest {

    /**
     * 业务目的：管理员 Web 请求产生的写审计必须记录稳定账号标识，不能继续全部记成 SYSTEM。
     */
    @Test
    void authenticatedAdminIsRecordedAsAuditActor() {
        MutableSessionPort sessions = new MutableSessionPort();
        sessions.actor = Optional.of(new AuthenticatedActor("admin", "管理员", WebRole.ADMIN));
        AuthenticatedActorProvider actorProvider = new AuthenticatedActorProvider(sessions);
        AuditMetadataFactory factory = new AuditMetadataFactory(
                () -> Instant.parse("2026-07-29T12:00:00Z"), actorProvider);

        AuditMetadata metadata = factory.created();

        assertThat(metadata.createdBy()).isEqualTo("admin");
        assertThat(metadata.updatedBy()).isEqualTo("admin");
    }

    /**
     * 业务目的：后台、启动恢复和其他没有 Web 身份的机器工作必须保留 SYSTEM，不能冒充最近登录用户。
     */
    @Test
    void missingWebIdentityUsesSystemActor() {
        AuthenticatedActorProvider actorProvider = new AuthenticatedActorProvider(new MutableSessionPort());

        assertThat(actorProvider.currentActor()).isEqualTo("SYSTEM");
    }

    /**
     * 业务目的：请求会话清理后下一项机器工作必须立即回到 SYSTEM，防止线程复用泄露上一个管理员身份。
     */
    @Test
    void clearedRequestIdentityDoesNotLeakIntoFollowingWork() {
        MutableSessionPort sessions = new MutableSessionPort();
        sessions.actor = Optional.of(new AuthenticatedActor("admin", "管理员", WebRole.ADMIN));
        AuthenticatedActorProvider actorProvider = new AuthenticatedActorProvider(sessions);
        assertThat(actorProvider.currentActor()).isEqualTo("admin");

        sessions.actor = Optional.empty();

        assertThat(actorProvider.currentActor()).isEqualTo("SYSTEM");
    }

    private static final class MutableSessionPort implements WebSessionPort {
        private Optional<AuthenticatedActor> actor = Optional.empty();

        @Override
        public void establish(AuthenticatedActor actor) {
            this.actor = Optional.of(actor);
        }

        @Override
        public Optional<AuthenticatedActor> current() {
            return actor;
        }

        @Override
        public void clear() {
            actor = Optional.empty();
        }
    }
}
