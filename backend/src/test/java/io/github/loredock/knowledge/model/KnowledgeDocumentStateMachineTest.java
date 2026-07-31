package io.github.loredock.knowledge.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.exception.DocumentStateConflictException;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeDocumentStateMachineTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-30T01:00:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-07-30T02:00:00Z");

    /**
     * 业务目的：人工创建只能得到 revision 1 的草稿并记录创建审计，防止未审核内容直接进入发布态。
     */
    @Test
    void createStartsDraftAtFirstRevisionWithAudit() {
        KnowledgeDocument document = KnowledgeDocument.create(
                8000000000000000042L, fields("初始正文"), audit(CREATED_AT, "admin"));

        assertThat(document.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(document.revision()).isEqualTo(new DocumentRevision(1));
        assertThat(document.createdAt()).isEqualTo(CREATED_AT);
        assertThat(document.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(document.createdBy()).isEqualTo("admin");
        assertThat(document.publishedAt()).isNull();
        assertThat(document.archivedAt()).isNull();
    }

    /**
     * 业务目的：完整字段未变化的重复保存必须返回当前聚合，不增加 revision 或更新时间，防止网络重试制造虚假修订。
     */
    @Test
    void editWithSameValuesIsIdempotent() {
        KnowledgeDocument original = draft();

        KnowledgeDocument unchanged = original.edit(original.fields(), audit(CHANGED_AT, "other-admin"));

        assertThat(unchanged).isSameAs(original);
        assertThat(unchanged.revision()).isEqualTo(new DocumentRevision(1));
        assertThat(unchanged.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(unchanged.updatedBy()).isEqualTo("admin");
    }

    /**
     * 业务目的：有效编辑应原子替换全部可编辑字段并只增加一次 revision，防止部分字段与修订号不一致。
     */
    @Test
    void editWithChangedValuesIncrementsRevisionOnce() {
        KnowledgeDocument original = draft();
        KnowledgeDocumentFields changedFields = fields("更新正文");

        KnowledgeDocument changed = original.edit(changedFields, audit(CHANGED_AT, "editor"));

        assertThat(changed.fields()).isEqualTo(changedFields);
        assertThat(changed.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(changed.revision()).isEqualTo(new DocumentRevision(2));
        assertThat(changed.updatedAt()).isEqualTo(CHANGED_AT);
        assertThat(changed.updatedBy()).isEqualTo("editor");
    }

    /**
     * 业务目的：首次发布必须记录发布人与发布时间并增加 revision，供普通浏览资格和索引待同步状态使用。
     */
    @Test
    void firstPublishRecordsAuditAndIncrementsRevision() {
        KnowledgeDocument published = draft().publish(audit(CHANGED_AT, "publisher"));

        assertThat(published.status()).isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(published.revision()).isEqualTo(new DocumentRevision(2));
        assertThat(published.publishedAt()).isEqualTo(CHANGED_AT);
        assertThat(published.publishedBy()).isEqualTo("publisher");
        assertThat(published.updatedAt()).isEqualTo(CHANGED_AT);
    }

    /**
     * 业务目的：已发布文档的重复发布必须幂等，防止覆盖首次发布时间或重复推动索引副作用。
     */
    @Test
    void repeatedPublishKeepsOriginalPublicationState() {
        KnowledgeDocument published = draft().publish(audit(CHANGED_AT, "publisher"));

        KnowledgeDocument repeated = published.publish(
                audit(CHANGED_AT.plusSeconds(60), "other-publisher"));

        assertThat(repeated).isSameAs(published);
        assertThat(repeated.revision()).isEqualTo(new DocumentRevision(2));
        assertThat(repeated.publishedAt()).isEqualTo(CHANGED_AT);
        assertThat(repeated.publishedBy()).isEqualTo("publisher");
    }

    /**
     * 业务目的：草稿和已发布文档都可归档，并记录归档审计和新修订，确保提交后立即失去实时资格。
     */
    @Test
    void draftAndPublishedDocumentsCanBeArchived() {
        KnowledgeDocument archivedDraft = draft().archive(audit(CHANGED_AT, "archiver"));
        KnowledgeDocument published = draft().publish(audit(CHANGED_AT, "publisher"));
        KnowledgeDocument archivedPublished = published.archive(
                audit(CHANGED_AT.plusSeconds(60), "archiver"));

        assertThat(archivedDraft.status()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(archivedDraft.revision()).isEqualTo(new DocumentRevision(2));
        assertThat(archivedDraft.archivedBy()).isEqualTo("archiver");
        assertThat(archivedPublished.status()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(archivedPublished.revision()).isEqualTo(new DocumentRevision(3));
    }

    /**
     * 业务目的：归档是终态，重复归档可幂等读取，但编辑或发布必须冲突，防止历史知识被原地恢复。
     */
    @Test
    void archivedDocumentIsTerminalExceptIdempotentArchive() {
        KnowledgeDocument archived = draft().archive(audit(CHANGED_AT, "archiver"));

        assertThat(archived.archive(audit(CHANGED_AT.plusSeconds(60), "other"))).isSameAs(archived);
        assertThatThrownBy(() -> archived.edit(fields("不能恢复"), audit(CHANGED_AT, "editor")))
                .isInstanceOf(DocumentStateConflictException.class);
        assertThatThrownBy(() -> archived.publish(audit(CHANGED_AT, "publisher")))
                .isInstanceOf(DocumentStateConflictException.class);
    }

    private KnowledgeDocument draft() {
        return KnowledgeDocument.create(8000000000000000043L, fields("初始正文"), audit(CREATED_AT, "admin"));
    }

    private KnowledgeDocumentFields fields(String body) {
        return new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN,
                new DocumentTitle("知识标题"),
                new DocumentBody(body),
                new DocumentDirectory("业务/规则"),
                new DocumentTags(List.of(DocumentTag.of("API"))),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "人工整理"),
                KnowledgeScope.global()
        );
    }

    private DocumentAudit audit(Instant at, String actor) {
        return new DocumentAudit(at, actor);
    }
}
