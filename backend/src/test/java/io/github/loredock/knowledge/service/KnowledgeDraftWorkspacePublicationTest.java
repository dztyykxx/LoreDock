package io.github.loredock.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.knowledge.api.KnowledgeDraftException;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import io.github.loredock.knowledge.mapper.KnowledgeDraftMapper;
import io.github.loredock.knowledge.mapper.KnowledgeDraftRevisionMapper;
import io.github.loredock.knowledge.mapper.KnowledgeDraftRevisionSourceMapper;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.KnowledgeDocumentFields;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.entity.KnowledgeDraftEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeDraftRevisionEntity;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.project.api.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeDraftWorkspacePublicationTest {
    private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");
    private final ProjectService projects = mock(ProjectService.class);
    private final KnowledgeDocumentDataService documents = mock(KnowledgeDocumentDataService.class);
    private final KnowledgeDocumentLifecycleService lifecycle = mock(KnowledgeDocumentLifecycleService.class);
    private final KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
    private final KnowledgeDraftRevisionMapper revisions = mock(KnowledgeDraftRevisionMapper.class);
    private final KnowledgeDraftRevisionSourceMapper sources = mock(KnowledgeDraftRevisionSourceMapper.class);
    private final KnowledgeIndexJobService indexJobs = mock(KnowledgeIndexJobService.class);
    private KnowledgeDraftServiceImpl service;

    @BeforeEach
    void createService() {
        service = new KnowledgeDraftServiceImpl(
                projects, documents, lifecycle, drafts, revisions, sources, indexJobs,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 业务目的：模型常用空字符串表达可选区块 ID，空草稿首次插入必须按无目标区块处理。 */
    @Test
    void normalizesBlankModelTargetBlockIdToNull() throws Exception {
        KnowledgeDraftService.UpdateOperation operation = new ObjectMapper().readValue("""
                {"type":"INSERT_AFTER","targetBlockId":"","markdown":"# 导入错误与重试动作",
                 "sourceRefs":[{"type":"SELECTED_DRAFT","sourceId":9}]}
                """, KnowledgeDraftService.UpdateOperation.class);

        assertThat(operation.targetBlockId()).isNull();
        assertThat(operation.type()).isEqualTo(KnowledgeDraftService.OperationType.INSERT_AFTER);
    }

    /**
     * 业务目的：同一发布请求必须让 ADD 创建新正式 ID，同时让 MODIFY 在原正式 ID 上增加修订，
     * 并且整个工作区只提交一次索引任务。
     */
    @Test
    void publishesAddAndModifyWithOneIndexSubmission() {
        KnowledgeDraftEntity addition = draft(51L, "ADD", null, null, 2L, "新增流程", "团队协作");
        KnowledgeDraftEntity modification = draft(52L, "MODIFY", 100L, 5L, 3L, "旧标题不会采用", "");
        KnowledgeDraftRevisionEntity additionRevision = revision(51L, 2L, "# 新增流程\n正文");
        KnowledgeDraftRevisionEntity modificationRevision = revision(52L, 3L, "# 既有规则\n修改后的正文");
        KnowledgeDocument baseline = document(100L, "既有规则", "项目规范", "旧正文", "发布", 5);
        KnowledgeDocument candidate = KnowledgeDocument.create(
                200L, fields("新增流程", "团队协作", "# 新增流程\n正文"), new DocumentAudit(NOW, "admin"));

        when(drafts.selectVisibleForUpdate(eq(51L), any(), any(), any())).thenReturn(addition);
        when(drafts.selectVisibleForUpdate(eq(52L), any(), any(), any())).thenReturn(modification);
        when(drafts.selectList(any())).thenReturn(List.of(addition, modification));
        when(revisions.selectOne(any())).thenReturn(
                additionRevision, modificationRevision, additionRevision, modificationRevision);
        when(documents.findAllByIdsForUpdate(List.of(100L))).thenReturn(List.of(baseline));
        when(documents.projectDirectoryExists(10L, "团队协作")).thenReturn(true);
        when(documents.existsPublishedProjectTitle(10L, "团队协作", "新增流程")).thenReturn(false);
        when(documents.insertDraft(any(), any())).thenReturn(candidate);
        when(documents.update(any(), any())).thenReturn(true);
        when(drafts.markPublished(any(), any(Long.class), any(), any())).thenReturn(1);

        KnowledgeDraftService.WorkspacePublication result = service.publishWorkspace(
                new KnowledgeDraftService.WorkspacePublishRequest(context(), List.of(
                        new KnowledgeDraftService.ReviewedDraft(51L, 2L),
                        new KnowledgeDraftService.ReviewedDraft(52L, 3L))));

        ArgumentCaptor<KnowledgeDocument> published = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documents, org.mockito.Mockito.times(2)).update(published.capture(), any());
        assertThat(published.getAllValues()).extracting(KnowledgeDocument::id).containsExactly(200L, 100L);
        KnowledgeDocument modified = published.getAllValues().get(1);
        assertThat(modified.fields().title().value()).isEqualTo("既有规则");
        assertThat(modified.fields().directory().value()).isEqualTo("项目规范");
        assertThat(modified.fields().body().value()).contains("修改后的正文");
        assertThat(result.documents()).extracting(KnowledgeDraftService.Publication::documentId)
                .containsExactly(200L, 100L);
        verify(indexJobs).submit();
    }

    /** 业务目的：审核集合中的任一 revision 不是当前值时，正式知识和索引任务都不得产生副作用。 */
    @Test
    void rejectsStaleReviewedSetBeforeFormalWrites() {
        KnowledgeDraftEntity addition = draft(51L, "ADD", null, null, 2L, "新增流程", "团队协作");
        when(drafts.selectVisibleForUpdate(eq(51L), any(), any(), any())).thenReturn(addition);
        when(drafts.selectList(any())).thenReturn(List.of(addition));
        when(revisions.selectOne(any())).thenReturn(revision(51L, 2L, "正文"));

        assertThatThrownBy(() -> service.publishWorkspace(
                new KnowledgeDraftService.WorkspacePublishRequest(context(), List.of(
                        new KnowledgeDraftService.ReviewedDraft(51L, 1L)))))
                .isInstanceOf(KnowledgeDraftException.class)
                .extracting("code").isEqualTo(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);

        verify(documents, never()).insertDraft(any(), any());
        verify(documents, never()).update(any(), any());
        verify(indexJobs, never()).submit();
    }

    /**
     * 业务目的：新增工作文档的错误标题必须以幂等且可审核的新修订纠正；
     * 防止直接改元数据导致当前修订、Diff 和发布审核指针失配。
     */
    @Test
    void renamesAdditionAndAdvancesRevisionWithoutChangingMarkdown() {
        KnowledgeDraftEntity addition = draft(51L, "ADD", null, null, 2L, "退款规则", "团队协作");
        KnowledgeDraftRevisionEntity base = revision(51L, 2L, "# 退款规则\n正文");
        when(drafts.selectVisibleForUpdate(eq(51L), any(), any(), any())).thenReturn(addition);
        when(revisions.selectOne(any())).thenReturn(null, base);
        when(drafts.renameAndAdvance(51L, 2L, 3L, "退款审批规则", NOW)).thenReturn(1);

        KnowledgeDraftService.DraftRevision renamed = service.rename(new KnowledgeDraftService.RenameRequest(
                context(), 51L, 2L, "rename-1", "退款审批规则", "纠正标题"));

        ArgumentCaptor<KnowledgeDraftRevisionEntity> created =
                ArgumentCaptor.forClass(KnowledgeDraftRevisionEntity.class);
        verify(revisions).insert(created.capture());
        assertThat(created.getValue().getRevision()).isEqualTo(3L);
        assertThat(created.getValue().getMarkdown()).isEqualTo(base.getMarkdown());
        assertThat(renamed.title()).isEqualTo("退款审批规则");
        assertThat(renamed.revision()).isEqualTo(3L);
        System.out.println("测试证据：场景=ADD草稿改名，draft=51，修订=2->3，正文保持=true");
    }

    /** 业务目的：纯标题改名产生的同正文修订必须显示为零行变更，防止审核页误报整篇重写。 */
    @Test
    void reportsNoContentDiffForRenameOnlyRevision() {
        KnowledgeDraftEntity addition = draft(51L, "ADD", null, null, 3L, "退款审批规则", "团队协作");
        String markdown = "# 退款规则\n正文";
        when(drafts.selectOne(any())).thenReturn(addition);
        when(revisions.selectOne(any())).thenReturn(
                revision(51L, 2L, markdown), revision(51L, 3L, markdown));

        KnowledgeDraftService.DraftDiff diff = service.diff(new KnowledgeDraftService.DiffRequest(
                context(), 51L, 2L, 3L));

        assertThat(diff.additions()).isZero();
        assertThat(diff.deletions()).isZero();
        assertThat(diff.unifiedDiff()).doesNotContain("-# 退款规则", "+# 退款规则");
        System.out.println("测试证据：场景=纯改名Diff，draft=51，正文变更=+0/-0");
    }

    private KnowledgeDraftService.AccessContext context() {
        return new KnowledgeDraftService.AccessContext("admin", "atlas", 41L, 61L);
    }

    private KnowledgeDraftEntity draft(
            Long id, String operation, Long baselineId, Long baselineRevision,
            Long currentRevision, String title, String directory
    ) {
        return KnowledgeDraftEntity.builder().id(id).conversationId(41L).operatorId("admin")
                .projectId(10L).projectIdentifier("atlas").operation(operation)
                .baselineDocumentId(baselineId).baselineRevision(baselineRevision)
                .currentRevision(currentRevision).title(title).directoryPath(directory)
                .createRunId(61L).build();
    }

    private KnowledgeDraftRevisionEntity revision(Long draftId, Long revision, String markdown) {
        return KnowledgeDraftRevisionEntity.builder().id(draftId * 10 + revision).draftId(draftId)
                .revision(revision).markdown(markdown).blocksJson("[]")
                .changeSummary("已审核").createdByRunId(61L).createdAt(NOW).build();
    }

    private KnowledgeDocument document(
            Long id, String title, String directory, String body, String tag, long revision
    ) {
        KnowledgeDocument value = KnowledgeDocument.create(
                id, new KnowledgeDocumentFields(DocumentFormat.MARKDOWN, new DocumentTitle(title),
                        new DocumentBody(body), new DocumentDirectory(directory), DocumentTags.of(List.of(tag)),
                        new DocumentSource(DocumentSourceType.MANUAL, null, null, "测试"),
                        KnowledgeScope.project(10L)), new DocumentAudit(NOW.minusSeconds(60), "admin"));
        value = value.publish(new DocumentAudit(NOW.minusSeconds(50), "admin"));
        while (value.revision().value() < revision) {
            value = value.edit(new KnowledgeDocumentFields(
                    value.fields().format(), value.fields().title(),
                    new DocumentBody(value.fields().body().value() + " "), value.fields().directory(),
                    value.fields().tags(), value.fields().source(), value.fields().scope()),
                    new DocumentAudit(value.updatedAt().plusSeconds(1), "admin"));
        }
        return value;
    }

    private KnowledgeDocumentFields fields(String title, String directory, String body) {
        return new KnowledgeDocumentFields(DocumentFormat.MARKDOWN, new DocumentTitle(title),
                new DocumentBody(body), new DocumentDirectory(directory), DocumentTags.of(List.of()),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "测试"),
                KnowledgeScope.project(10L));
    }
}
