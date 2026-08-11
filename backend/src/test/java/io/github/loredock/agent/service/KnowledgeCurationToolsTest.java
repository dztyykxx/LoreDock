package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskSelectedDraftMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskSelectedDraftEntity;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import io.github.loredock.knowledge.api.KnowledgeDocumentAccessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

/** 验证知识整理只暴露有限标准 ToolCallback，并在业务调用前复核服务端运行范围。 */
class KnowledgeCurationToolsTest {

    /**
     * 业务目的：Agent 只能读取会话启动时固定的 Markdown 快照；
     * 防止模型通过猜测文档 ID 读取未勾选草稿。
     */
    @Test
    @SuppressWarnings("unchecked")
    void selectedDraftReadReturnsOnlyBoundedConversationSnapshotPage() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        KnowledgeTaskSelectedDraftMapper selected = mock(KnowledgeTaskSelectedDraftMapper.class);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        when(selected.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                KnowledgeTaskSelectedDraftEntity.builder().documentId(81L).documentRevision(3L)
                        .title("退款规则").directoryPath("交易/售后").originalFilename("refund.md")
                        .markdown("甲😀乙丙丁").ordinal(0).build()));
        KnowledgeCurationTools tools = new KnowledgeCurationTools(
                mock(ProjectQaToolService.class), mock(AgentEvidenceService.class), mock(ObjectProvider.class),
                runs, mock(KnowledgeTaskMessageMapper.class), selected,
                mock(KnowledgeDocumentAccessService.class));
        ToolContext context = new ToolContext(Map.of(
                "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L, "runId", 61L));

        KnowledgeCurationTools.SelectedDraftContent result = tools.selectedDraftRead(81L, 1, 2, context);

        assertThat(result.documentId()).isEqualTo(81L);
        assertThat(result.revision()).isEqualTo(3L);
        assertThat(result.markdown()).isEqualTo("😀乙");
        assertThat(result.cursor()).isEqualTo(1);
        assertThat(result.nextCursor()).isEqualTo(3);
        assertThat(result.totalCodePoints()).isEqualTo(5);
        assertThat(result.truncated()).isTrue();
        assertThatThrownBy(() -> tools.selectedDraftRead(82L, 0, 2, context))
                .hasMessage("当前任务未勾选该草稿");
        System.out.println("测试证据：场景=勾选草稿分段读取，会话=41，cursor=1，nextCursor=3，Unicode码点数=2");
    }

    /**
     * 业务目的：候选 Tool 集不得包含正式发布、Shell、任意 HTTP 或文件写能力，防止单 Agent 获得越权能力。
     */
    @Test
    void exposesOnlyExplicitCurationBusinessTools() {
        List<ToolCallback> callbacks = callbacks(mock(AgentRunMapper.class), mock(ProjectQaToolService.class));
        List<String> names = callbacks.stream()
                .map(value -> value.getToolDefinition().name()).toList();

        assertThat(names).containsExactly(
                "draft_create", "draft_diff", "draft_read", "draft_rename", "draft_update",
                "knowledge_directory_list", "knowledge_document_list", "knowledge_document_read",
                "knowledge_grep", "knowledge_search", "selected_draft_list", "selected_draft_read",
                "workspace_document_list");
        assertThat(callbacks).allSatisfy(callback -> assertThat(callback).isInstanceOf(MethodToolCallback.class));
        assertThat(names).doesNotContain("publish", "shell", "http", "write_file");
        System.out.printf("测试证据：场景=知识Tool允许集，Tool数=%d，发布/Shell/HTTP/文件写=0%n", names.size());
    }

    /**
     * 业务目的：注解方法必须维持平坦模型参数，并把服务端 ToolContext 排除在 Schema 外；
     * 防止迁移到 Method Tool 后出现 input 包装层或把 runId 暴露给模型。
     */
    @Test
    void annotatedMethodToolKeepsFlatSchemaAndHidesToolContext() {
        List<ToolCallback> callbacks = callbacks(mock(AgentRunMapper.class), mock(ProjectQaToolService.class));
        ToolCallback search = callbacks.stream()
                .filter(value -> value.getToolDefinition().name().equals("knowledge_search"))
                .findFirst().orElseThrow();
        ToolCallback update = callbacks.stream()
                .filter(value -> value.getToolDefinition().name().equals("draft_update"))
                .findFirst().orElseThrow();
        ToolCallback selectedRead = callbacks.stream()
                .filter(value -> value.getToolDefinition().name().equals("selected_draft_read"))
                .findFirst().orElseThrow();

        String schema = search.getToolDefinition().inputSchema();

        assertThat(schema).contains(
                        "\"query\"", "\"limit\"", "要检索的项目知识问题", "期望返回数量")
                .doesNotContain("\"input\"", "ToolContext", "runId", "operatorId");
        assertThat(update.getToolDefinition().description())
                .contains("可直接发布", "禁止把待确认问题、警告或执行过程写入文档");
        assertThat(selectedRead.getToolDefinition().inputSchema()).contains("起始 Unicode 码点游标", "本次最大返回码点数");
        System.out.println("测试证据：场景=注解Tool参数Schema，分段参数=cursor+maxCodePoints，服务端上下文字段=0");
    }

    /**
     * 业务目的：Agent 选错新增工作文档标题后必须能在当前修订上纠正；
     * 防止只能新建重复文档来规避错误标题。
     */
    @Test
    @SuppressWarnings("unchecked")
    void draftRenameDelegatesCurrentScopeAndRevision() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        KnowledgeDraftService drafts = mock(KnowledgeDraftService.class);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(drafts);
        when(drafts.rename(org.mockito.ArgumentMatchers.any())).thenReturn(new KnowledgeDraftService.DraftRevision(
                51L, 3, KnowledgeDraftService.WorkspaceOperation.ADD, null, null,
                "退款审批规则", "交易/售后", "标题纠正后的完整正文",
                List.of(new KnowledgeDraftService.DraftBlock("b-1", "标题纠正后的完整正文")),
                List.of(), "纠正文档标题", 61L, Instant.now()));
        KnowledgeCurationTools tools = new KnowledgeCurationTools(
                mock(ProjectQaToolService.class), mock(AgentEvidenceService.class), provider, runs,
                mock(KnowledgeTaskMessageMapper.class), mock(KnowledgeTaskSelectedDraftMapper.class),
                mock(KnowledgeDocumentAccessService.class));
        ToolContext context = new ToolContext(Map.of(
                "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L, "runId", 61L));

        KnowledgeCurationTools.DraftWriteResult result = tools.draftRename(
                51L, 2, "rename-1", "退款审批规则", "纠正文档标题", context);

        assertThat(result.revision()).isEqualTo(3);
        assertThat(result.title()).isEqualTo("退款审批规则");
        assertThat(result.toString()).doesNotContain("标题纠正后的完整正文");
        verify(drafts).rename(org.mockito.ArgumentMatchers.argThat(request ->
                request.draftId().equals(51L) && request.baseRevision() == 2
                        && request.title().equals("退款审批规则")
                        && request.context().conversationId().equals(41L)));
        System.out.println("测试证据：场景=工作文档改名回执，draft=51，新修订=3，回传正文=0");
    }

    /**
     * 业务目的：模型即使构造 operator/project/run 参数也不能越过服务端 ToolContext 与持久化 run 的交叉校验。
     */
    @Test
    void rejectsSpoofedToolContextBeforeCallingKnowledgeService() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        ProjectQaToolService knowledge = mock(ProjectQaToolService.class);
        ToolCallback search = callbacks(runs, knowledge).stream()
                .filter(value -> value.getToolDefinition().name().equals("knowledge_search"))
                .findFirst().orElseThrow();
        ToolContext spoofed = new ToolContext(Map.of(
                "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L, "runId", 61L));

        assertThatThrownBy(() -> search.call("{\"query\":\"范围\",\"limit\":3}", spoofed))
                .isInstanceOf(ToolExecutionException.class)
                .hasRootCauseMessage("知识 Tool 上下文与运行固定范围不一致");
        verifyNoInteractions(knowledge);
        System.out.println("测试证据：场景=ToolContext防伪，持久化run匹配=false，业务检索调用=0");
    }

    /**
     * 业务目的：草稿来源必须属于当前 run 证据或当前会话用户消息；
     * 防止模型用其他任务的 ID 伪造本修订来源关系。
     */
    @Test
    @SuppressWarnings("unchecked")
    void rejectsDraftSourceOutsideCurrentRunBeforeCallingDraftService() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentEvidenceService evidence = mock(AgentEvidenceService.class);
        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        KnowledgeTaskSelectedDraftMapper selected = mock(KnowledgeTaskSelectedDraftMapper.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        KnowledgeDraftService drafts = mock(KnowledgeDraftService.class);
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(drafts);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        when(evidence.findByRunId(61L)).thenReturn(List.of());
        when(messages.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(selected.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        KnowledgeCurationTools tools = new KnowledgeCurationTools(
                mock(ProjectQaToolService.class), evidence, provider, runs, messages, selected,
                mock(KnowledgeDocumentAccessService.class));
        ToolCallback update = List.of(MethodToolCallbackProvider.builder().toolObjects(tools).build()
                        .getToolCallbacks()).stream()
                .filter(value -> value.getToolDefinition().name().equals("draft_update"))
                .findFirst().orElseThrow();
        ToolContext context = new ToolContext(Map.of(
                "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L, "runId", 61L));
        String input = """
                {"draftId":51,"baseRevision":2,"idempotencyKey":"call-3","changeSummary":"补充事实",
                 "operations":[{"type":"INSERT_AFTER","targetBlockId":null,"markdown":"新事实",
                 "sourceRefs":[{"type":"EVIDENCE","sourceId":999}]}]}
                """;

        assertThatThrownBy(() -> update.call(input, context))
                .isInstanceOf(ToolExecutionException.class)
                .hasRootCauseMessage("草稿修订引用了当前 run 或会话之外的来源");
        verifyNoInteractions(drafts);
        System.out.println("测试证据：场景=草稿来源归属，run=61，越界evidence=999，草稿写入=0");
    }

    /**
     * 业务目的：模型用 0 表示“没有正式知识基线”时必须创建空基线草稿；
     * 防止可选 ID 的占位值被当作文档标识查询并终止整轮知识整理。
     */
    @Test
    @SuppressWarnings("unchecked")
    void draftCreateTreatsZeroBaselineAsAbsent() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        KnowledgeDraftService drafts = mock(KnowledgeDraftService.class);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(drafts);
        when(drafts.create(org.mockito.ArgumentMatchers.any())).thenReturn(new KnowledgeDraftService.DraftRevision(
                61L, 0, KnowledgeDraftService.WorkspaceOperation.ADD, null, null,
                "新的业务知识", "", "初始基线正文", List.of(), List.of(), "初始基线", 61L, Instant.now()));
        KnowledgeCurationTools tools = new KnowledgeCurationTools(
                mock(ProjectQaToolService.class), mock(AgentEvidenceService.class), provider, runs,
                mock(KnowledgeTaskMessageMapper.class), mock(KnowledgeTaskSelectedDraftMapper.class),
                mock(KnowledgeDocumentAccessService.class));
        ToolContext context = new ToolContext(Map.of(
                "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L, "runId", 61L));

        KnowledgeCurationTools.DraftWriteResult result = tools.draftCreate("create-gap-1", "新的业务知识", 0L, context);

        assertThat(result.draftId()).isEqualTo(61L);
        assertThat(result.revision()).isZero();
        assertThat(result.blockIds()).isEmpty();
        assertThat(result.toString()).doesNotContain("初始基线正文");
        verify(drafts).create(org.mockito.ArgumentMatchers.argThat(request ->
                request.baselineDocumentId() == null && request.context().projectIdentifier().equals("atlas")));
        System.out.println("测试证据：场景=空基线创建回执，模型baseline=0，服务端baseline=null，回传正文=0");
    }

    /**
     * 业务目的：写类工具结果不得把模型刚提交的正文原样回传，避免大段内容反复占用 Agent 上下文；
     * 同时必须保留服务端新分配的稳定区块 ID，供后续 INSERT_AFTER/REPLACE_BLOCK 继续引用。
     */
    @Test
    @SuppressWarnings("unchecked")
    void draftUpdateReturnsLightReceiptWithoutEchoedMarkdown() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentEvidenceService evidence = mock(AgentEvidenceService.class);
        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        KnowledgeTaskSelectedDraftMapper selected = mock(KnowledgeTaskSelectedDraftMapper.class);
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        KnowledgeDraftService drafts = mock(KnowledgeDraftService.class);
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(drafts);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        when(evidence.findByRunId(61L)).thenReturn(List.of());
        when(messages.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(selected.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        String insertedMarkdown = "已插入的大段业务正文";
        when(drafts.update(org.mockito.ArgumentMatchers.any())).thenReturn(new KnowledgeDraftService.DraftRevision(
                51L, 3, KnowledgeDraftService.WorkspaceOperation.MODIFY, 91L, 2L,
                "退款规则", "交易/售后", insertedMarkdown,
                List.of(new KnowledgeDraftService.DraftBlock("b-1", "既有区块"),
                        new KnowledgeDraftService.DraftBlock("b-2", insertedMarkdown)),
                List.of(), "补充退款时限事实", 61L, Instant.now()));
        KnowledgeCurationTools tools = new KnowledgeCurationTools(
                mock(ProjectQaToolService.class), evidence, provider, runs, messages, selected,
                mock(KnowledgeDocumentAccessService.class));
        ToolContext context = new ToolContext(Map.of(
                "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L, "runId", 61L));

        KnowledgeCurationTools.DraftWriteResult result = tools.draftUpdate(
                51L, 2, "call-3",
                List.of(new KnowledgeDraftService.UpdateOperation(
                        KnowledgeDraftService.OperationType.INSERT_AFTER, null, insertedMarkdown, List.of())),
                "补充退款时限事实", context);

        assertThat(result.revision()).isEqualTo(3);
        assertThat(result.blockIds()).containsExactly("b-1", "b-2");
        assertThat(result.toString()).doesNotContain(insertedMarkdown);
        System.out.println("测试证据：场景=写更新轻量回执，新修订=3，稳定区块ID=2，回传正文=0");
    }

    @SuppressWarnings("unchecked")
    private List<ToolCallback> callbacks(AgentRunMapper runs, ProjectQaToolService knowledge) {
        ObjectProvider<KnowledgeDraftService> drafts = mock(ObjectProvider.class);
        KnowledgeTaskSelectedDraftMapper selected = mock(KnowledgeTaskSelectedDraftMapper.class);
        when(selected.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        KnowledgeCurationTools tools = new KnowledgeCurationTools(
                knowledge, mock(AgentEvidenceService.class), drafts, runs,
                mock(KnowledgeTaskMessageMapper.class), selected, mock(KnowledgeDocumentAccessService.class));
        return List.of(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .stream().sorted(java.util.Comparator.comparing(value -> value.getToolDefinition().name())).toList();
    }
}
