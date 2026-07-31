package io.github.loredock.knowledge.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.exception.DocumentReplacementConflictException;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplacementPublicationPlannerTest {

    private static final DocumentAudit CREATED = new DocumentAudit(
            Instant.parse("2026-07-30T01:00:00Z"), "admin");
    private static final DocumentAudit PUBLISHING = new DocumentAudit(
            Instant.parse("2026-07-30T02:00:00Z"), "publisher");

    private final ReplacementPublicationPlanner planner = new ReplacementPublicationPlanner();

    /**
     * 业务目的：文档不能替代自身，防止一条记录同时成为新旧知识而破坏追溯方向。
     */
    @Test
    void selfReplacementIsRejected() {
        KnowledgeDocument document = draft(8000000000000000025L, KnowledgeScope.global());

        assertThatThrownBy(() -> planner.plan(
                document, document, new ReplacementChain(List.of(document.id())), PUBLISHING))
                .isInstanceOf(DocumentReplacementConflictException.class);
    }

    /**
     * 业务目的：新旧文档必须具有完全相同的范围，防止替代操作把一个项目或分支的知识归档到另一范围。
     */
    @Test
    void differentScopeReplacementIsRejected() {
        KnowledgeDocument candidate = draft(8000000000000000026L, KnowledgeScope.global());
        KnowledgeDocument old = published(8000000000000000027L, KnowledgeScope.project(8000000000000000028L));

        assertThatThrownBy(() -> planner.plan(
                candidate, old, new ReplacementChain(List.of(old.id())), PUBLISHING))
                .isInstanceOf(DocumentReplacementConflictException.class);
    }

    /**
     * 业务目的：替代链回到候选文档时必须拒绝，防止追溯遍历出现循环和无限读取。
     */
    @Test
    void cyclicReplacementChainIsRejected() {
        KnowledgeDocument candidate = draft(8000000000000000029L, KnowledgeScope.global());
        KnowledgeDocument old = published(8000000000000000030L, KnowledgeScope.global());

        assertThatThrownBy(() -> planner.plan(
                candidate, old, new ReplacementChain(List.of(old.id(), candidate.id())), PUBLISHING))
                .isInstanceOf(DocumentReplacementConflictException.class);
    }

    /**
     * 业务目的：只有当前已发布知识能被替代，防止草稿或归档文档被再次归档并伪造发布历史。
     */
    @Test
    void nonPublishedOldDocumentIsRejected() {
        KnowledgeDocument candidate = draft(8000000000000000031L, KnowledgeScope.global());
        KnowledgeDocument oldDraft = draft(8000000000000000032L, KnowledgeScope.global());

        assertThatThrownBy(() -> planner.plan(
                candidate, oldDraft, new ReplacementChain(List.of(oldDraft.id())), PUBLISHING))
                .isInstanceOf(DocumentReplacementConflictException.class);
    }

    /**
     * 业务目的：一个旧文档只能被一个当前文档替代，防止并发竞争覆盖已有被替代方向。
     */
    @Test
    void oldDocumentAlreadyClaimedByAnotherReplacementIsRejected() {
        Long oldId = 8000000000000000033L;
        KnowledgeDocument old = restoredPublishedWithIncomingReplacement(oldId, 8000000000000000034L);
        KnowledgeDocument candidate = draft(8000000000000000035L, KnowledgeScope.global());

        assertThatThrownBy(() -> planner.plan(
                candidate, old, new ReplacementChain(List.of(oldId)), PUBLISHING))
                .isInstanceOf(DocumentReplacementConflictException.class);
    }

    /**
     * 业务目的：合法替代发布必须一次产生新文档发布、旧文档归档和双向关系，供应用层原子持久化。
     */
    @Test
    void validReplacementProducesAtomicPublicationPlan() {
        KnowledgeDocument candidate = draft(8000000000000000036L, KnowledgeScope.global());
        KnowledgeDocument old = published(8000000000000000037L, KnowledgeScope.global());

        ReplacementPublicationPlan plan = planner.plan(
                candidate, old, new ReplacementChain(List.of(old.id())), PUBLISHING);

        assertThat(plan.publishedDocument().status()).isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(plan.publishedDocument().replacement().replacesDocumentId()).isEqualTo(old.id());
        assertThat(plan.publishedDocument().revision()).isEqualTo(new DocumentRevision(2));
        assertThat(plan.archivedDocument().status()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(plan.archivedDocument().replacement().replacedByDocumentId()).isEqualTo(candidate.id());
        assertThat(plan.archivedDocument().revision()).isEqualTo(new DocumentRevision(3));
    }

    private KnowledgeDocument draft(Long id, KnowledgeScope scope) {
        return KnowledgeDocument.create(id, fields(scope), CREATED);
    }

    private KnowledgeDocument published(Long id, KnowledgeScope scope) {
        return draft(id, scope).publish(new DocumentAudit(
                Instant.parse("2026-07-30T01:30:00Z"), "first-publisher"));
    }

    private KnowledgeDocument restoredPublishedWithIncomingReplacement(Long id, Long replacedById) {
        Instant publishedAt = Instant.parse("2026-07-30T01:30:00Z");
        return KnowledgeDocument.restore(
                id,
                fields(KnowledgeScope.global()),
                DocumentStatus.PUBLISHED,
                new DocumentRevision(2),
                new ReplacementLink(null, replacedById),
                publishedAt,
                "first-publisher",
                null,
                null,
                CREATED.at(),
                publishedAt,
                CREATED.actor(),
                "first-publisher"
        );
    }

    private KnowledgeDocumentFields fields(KnowledgeScope scope) {
        return new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN,
                new DocumentTitle("替代知识"),
                new DocumentBody("正文"),
                new DocumentDirectory("业务"),
                DocumentTags.of(List.of("规则")),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "人工整理"),
                scope
        );
    }
}
