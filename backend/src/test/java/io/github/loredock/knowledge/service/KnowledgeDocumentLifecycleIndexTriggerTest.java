package io.github.loredock.knowledge.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.knowledge.config.KnowledgeIndexJobTypes;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.KnowledgeDocumentFields;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.command.ArchiveKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.command.BatchPublishKnowledgeDocumentsCommand;
import io.github.loredock.knowledge.model.command.PublishKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.knowledge.converter.KnowledgeDocumentViewFactory;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.support.TestIds;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 生命周期操作与索引增量刷新的触发契约。
 *
 * <p>管理员文档页的发布、批量发布和归档走生命周期服务，任何一次成功操作都必须提交
 * REFRESH 增量刷新任务，只重算涉及的文档；全量重建只允许作为刷新内部的降级路径。</p>
 */
class KnowledgeDocumentLifecycleIndexTriggerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    private final KnowledgeDocumentDataService documents = mock(KnowledgeDocumentDataService.class);
    private final AuditMetadataFactory auditFactory = mock(AuditMetadataFactory.class);
    private final KnowledgeDocumentViewFactory views = mock(KnowledgeDocumentViewFactory.class);
    private final KnowledgeIndexJobService indexJobs = mock(KnowledgeIndexJobService.class);
    private final KnowledgeDocumentLifecycleService lifecycle = new KnowledgeDocumentLifecycleService(
            documents, auditFactory, views, indexJobs);

    @BeforeEach
    void configureMocks() {
        when(auditFactory.created()).thenReturn(new AuditMetadata(NOW, NOW, "operator", "operator"));
        when(documents.update(any(), any())).thenReturn(true);
        when(views.create(any())).thenReturn(mock(KnowledgeDocumentView.class));
    }

    /** 业务目的：管理员文档页单篇发布必须提交增量刷新任务，防止每次发布都触发全量重建索引。 */
    @Test
    void singlePublishSubmitsIncrementalRefresh() {
        KnowledgeDocument draft = draft("单篇发布");
        when(documents.findAllByIdsForUpdate(List.of(draft.id()))).thenReturn(List.of(draft));

        lifecycle.publish(new PublishKnowledgeDocumentCommand(draft.id(), null));

        verify(indexJobs).submit(KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH);
    }

    /** 业务目的：批量发布只要产生了实际发布就必须提交增量刷新任务，让新文档及时进入检索。 */
    @Test
    void batchPublishSubmitsIncrementalRefreshWhenAnyPublished() {
        KnowledgeDocument draft = draft("批量发布");
        when(documents.findAllByIdsForUpdate(List.of(draft.id()))).thenReturn(List.of(draft));

        lifecycle.publishBatch(new BatchPublishKnowledgeDocumentsCommand(List.of(draft.id())));

        verify(indexJobs).submit(KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH);
    }

    /** 业务目的：批量发布全部已是发布状态属于幂等重试，不产生变更，不得触发无意义的索引任务。 */
    @Test
    void batchPublishAllAlreadyPublishedDoesNotSubmit() {
        KnowledgeDocument published = draft("幂等重试").publish(new DocumentAudit(NOW, "publisher"));
        when(documents.findAllByIdsForUpdate(List.of(published.id()))).thenReturn(List.of(published));

        lifecycle.publishBatch(new BatchPublishKnowledgeDocumentsCommand(List.of(published.id())));

        verify(indexJobs, never()).submit(anyString());
    }

    /** 业务目的：归档文档必须提交增量刷新任务，及时移除其在索引中的分块，防止检索命中已归档内容。 */
    @Test
    void archiveSubmitsIncrementalRefresh() {
        KnowledgeDocument published = draft("待归档").publish(new DocumentAudit(NOW, "publisher"));
        when(documents.findAllByIdsForUpdate(List.of(published.id()))).thenReturn(List.of(published));

        lifecycle.archive(new ArchiveKnowledgeDocumentCommand(published.id()));

        verify(indexJobs).submit(KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH);
    }

    private KnowledgeDocument draft(String title) {
        return KnowledgeDocument.create(TestIds.next(), new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of("tag")),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated"), KnowledgeScope.global()),
                new DocumentAudit(NOW, "author"));
    }
}
