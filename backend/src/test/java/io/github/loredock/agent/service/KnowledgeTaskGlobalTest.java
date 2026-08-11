package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskPublicationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskSelectedDraftMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import io.github.loredock.knowledge.api.KnowledgeDocumentAccessService;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import io.github.loredock.knowledge.api.KnowledgeSearchService;
import io.github.loredock.project.api.ProjectService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 全局知识整理（整理通用业务知识）的任务会话范围测试。
 */
class KnowledgeTaskGlobalTest {

    private static final Long CONVERSATION_ID = 7010000000000000001L;
    private static final Long DRAFT_DOCUMENT_ID = 7010000000000000002L;
    private static final Long RUN_ID = 7010000000000000003L;
    private static final Instant NOW = Instant.parse("2026-08-10T02:00:00Z");
    private ProjectService projects;
    private KnowledgeTaskConversationMapper conversations;
    private KnowledgeTaskMessageMapper messages;
    private KnowledgeTaskSelectedDraftMapper selectedDrafts;
    private AgentRunMapper runs;
    private KnowledgeAgentDefinitionService definitions;
    private KnowledgeSearchService knowledgeSearch;
    private KnowledgeDraftService drafts;
    private KnowledgeDocumentAccessService documentAccess;
    private KnowledgeTaskEventService taskEvents;
    private KnowledgeToolInvocationService toolInvocations;
    private KnowledgeTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        conversations = mock(KnowledgeTaskConversationMapper.class);
        messages = mock(KnowledgeTaskMessageMapper.class);
        selectedDrafts = mock(KnowledgeTaskSelectedDraftMapper.class);
        runs = mock(AgentRunMapper.class);
        definitions = mock(KnowledgeAgentDefinitionService.class);
        knowledgeSearch = mock(KnowledgeSearchService.class);
        drafts = mock(KnowledgeDraftService.class);
        documentAccess = mock(KnowledgeDocumentAccessService.class);
        taskEvents = mock(KnowledgeTaskEventService.class);
        toolInvocations = mock(KnowledgeToolInvocationService.class);
        service = new KnowledgeTaskServiceImpl(
                projects, conversations, messages, selectedDrafts, runs,
                mock(PostgresSaver.class), definitions, knowledgeSearch,
                mock(io.github.loredock.agent.api.AgentService.class),
                mock(KnowledgeCurationRunExecutor.class), drafts, documentAccess,
                taskEvents, toolInvocations, mock(KnowledgeTaskPublicationMapper.class),
                new ObjectMapper(), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        // 先构造带 stub 的 LoadedDefinition，避免在 thenReturn 参数求值中嵌套 when 触发 UnfinishedStubbing。
        KnowledgeAgentDefinitionService.LoadedDefinition definition = loaded();
        when(definitions.load("knowledge-curator")).thenReturn(definition);
        when(conversations.insertIfAbsent(any())).thenReturn(CONVERSATION_ID);
        when(runs.insertKnowledgeRun(any())).thenReturn(RUN_ID);
        when(messages.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(runs.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(selectedDrafts.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(toolInvocations.list(any())).thenReturn(List.of());
        when(taskEvents.latest(any())).thenReturn(0L);
        when(knowledgeSearch.findActiveIndexVersionId()).thenReturn(java.util.Optional.of(1L));
    }

    /**
     * 业务目的：全局知识任务（projectIdentifier 为空）必须跳过项目主数据解析，
     * 只接受通用范围的待处理草稿，会话与运行落库 project_id 为空 + 哨兵标识。
     */
    @Test
    void globalStartSkipsProjectResolutionAndReadsOnlyGeneralDrafts() {
        when(documentAccess.readDraftsGlobal(List.of(DRAFT_DOCUMENT_ID))).thenReturn(List.of(
                new KnowledgeDocumentAccessService.DocumentContent(
                        DRAFT_DOCUMENT_ID, 1L, "通用草稿", "guides", "# 草稿", "draft.md", NOW)));

        KnowledgeTaskService.KnowledgeTask task = service.start(new KnowledgeTaskService.StartRequest(
                "global-key", "admin", null, List.of(DRAFT_DOCUMENT_ID),
                KnowledgeTaskService.TriggerType.MANUAL, "合并通用草稿", "knowledge-curator", "整理通用知识"));

        verify(projects, never()).resolveEnabledScope(any(), any());
        verify(documentAccess).readDraftsGlobal(List.of(DRAFT_DOCUMENT_ID));
        ArgumentCaptor<KnowledgeTaskConversationEntity> conversation =
                ArgumentCaptor.forClass(KnowledgeTaskConversationEntity.class);
        verify(conversations).insertIfAbsent(conversation.capture());
        assertThat(conversation.getValue().getProjectId()).isNull();
        assertThat(conversation.getValue().getProjectIdentifier()).isEqualTo("GLOBAL");
        ArgumentCaptor<AgentRunEntity> run = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).insertKnowledgeRun(run.capture());
        assertThat(run.getValue().getProjectId()).isNull();
        assertThat(run.getValue().getProjectIdentifier()).isEqualTo("GLOBAL");
        assertThat(run.getValue().getTaskType()).isEqualTo("knowledge_curation");
        System.out.printf("测试证据：场景=全局知识任务启动，conversationId=%s，projectId=null，哨兵=%s，runId=%s%n",
                task.conversationId(), conversation.getValue().getProjectIdentifier(), run.getValue().getId());
    }

    /**
     * 业务目的：全局任务列表必须只返回 project_id 为空的全局任务，项目任务不混入；
     * 防止通用知识页看到各项目的整理历史。
     */
    @Test
    void listGlobalFiltersOnlyProjectIdNullConversations() {
        // 单元测试没有 MyBatis 运行时，先注册实体表元数据使 LambdaQueryWrapper 可求值。
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                KnowledgeTaskConversationEntity.class);
        when(conversations.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<KnowledgeTaskService.KnowledgeTaskSummary> summaries = service.listGlobal("admin");

        ArgumentCaptor<Wrapper<KnowledgeTaskConversationEntity>> query =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(conversations).selectList(query.capture());
        assertThat(((com.baomidou.mybatisplus.core.conditions.Wrapper<KnowledgeTaskConversationEntity>) query.getValue())
                .getSqlSegment().toLowerCase()).contains("project_id is null");
        assertThat(summaries).isEmpty();
        System.out.println("测试证据：场景=全局任务列表，条件=project_id IS NULL，返回=0");
    }

    /**
     * 业务目的：全局任务选中的草稿混入项目草稿时必须在创建会话前被拒绝，
     * 防止把项目草稿快照进全局整理工作区。
     */
    @Test
    void globalStartRejectsProjectDraftSelectionBeforeConversation() {
        when(documentAccess.readDraftsGlobal(List.of(DRAFT_DOCUMENT_ID)))
                .thenThrow(new IllegalArgumentException("待处理草稿必须是通用范围的 Markdown DRAFT"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.start(
                        new KnowledgeTaskService.StartRequest(
                                "global-key", "admin", null, List.of(DRAFT_DOCUMENT_ID),
                                KnowledgeTaskService.TriggerType.MANUAL,
                                "合并通用草稿", "knowledge-curator", "整理通用知识")))
                .isInstanceOf(KnowledgeTaskRequestException.class);

        verify(conversations, never()).insertIfAbsent(any());
        verify(runs, never()).insertKnowledgeRun(any());
        System.out.println("测试证据：场景=全局任务草稿范围校验，项目草稿=拒绝，会话创建=0，运行创建=0");
    }

    private KnowledgeAgentDefinitionService.LoadedDefinition loaded() {
        KnowledgeTaskService.RuntimeDefinition runtime = new KnowledgeTaskService.RuntimeDefinition(
                "knowledge-curator", "digest", "agent-spec", "fake-model",
                List.of("selected_draft_list", "knowledge_search"));
        KnowledgeAgentDefinitionService.LoadedDefinition definition =
                mock(KnowledgeAgentDefinitionService.LoadedDefinition.class);
        when(definition.runtime()).thenReturn(runtime);
        return definition;
    }
}
