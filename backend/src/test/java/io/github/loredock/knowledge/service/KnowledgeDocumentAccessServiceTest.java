package io.github.loredock.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.knowledge.api.KnowledgeDocumentAccessService;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.KnowledgeDocumentFields;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeDocumentAccessServiceTest {

    /**
     * 业务目的：大文档必须按 Unicode 码点连续分段交给 Agent；
     * 防止 emoji 等代理对被截断，或者下一段游标造成正文重复和缺口。
     */
    @Test
    void readsPublishedMarkdownWithUnicodeCursorAndExplicitContinuation() {
        ProjectService projects = mock(ProjectService.class);
        KnowledgeDocumentDataService documents = mock(KnowledgeDocumentDataService.class);
        when(projects.resolveEnabledScope("atlas", null))
                .thenReturn(new ProjectScope(10L, "atlas", "Atlas", true, 11L, "main"));
        when(documents.findPublishedById(org.mockito.ArgumentMatchers.eq(81L), any()))
                .thenReturn(Optional.of(publishedDocument(81L, "甲😀乙丙丁")));
        KnowledgeDocumentAccessService service = new KnowledgeDocumentAccessServiceImpl(projects, documents);

        KnowledgeDocumentAccessService.DocumentPage page =
                service.readPublishedPage("atlas", 81L, 1, 2);

        assertThat(page.markdown()).isEqualTo("😀乙");
        assertThat(page.cursor()).isEqualTo(1);
        assertThat(page.nextCursor()).isEqualTo(3);
        assertThat(page.totalCodePoints()).isEqualTo(5);
        assertThat(page.truncated()).isTrue();
        System.out.println("测试证据：场景=已发布大文档分段，document=81，cursor=1，nextCursor=3，代理对完整=true");
    }

    private KnowledgeDocument publishedDocument(Long id, String body) {
        Instant now = Instant.parse("2026-08-10T10:00:00Z");
        KnowledgeDocument document = KnowledgeDocument.create(id, new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle("退款规则"), new DocumentBody(body),
                new DocumentDirectory("交易/售后"), DocumentTags.of(List.of()),
                new DocumentSource(DocumentSourceType.UPLOAD, null, "refund.md", "测试"),
                KnowledgeScope.project(10L)), new DocumentAudit(now, "admin"));
        return document.publish(new DocumentAudit(now.plusSeconds(1), "admin"));
    }
}
