package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;

/** 验证知识整理只暴露有限标准 ToolCallback，并在业务调用前复核服务端运行范围。 */
class KnowledgeCurationToolCallbacksTest {

    /**
     * 业务目的：候选 Tool 集不得包含正式发布、Shell、任意 HTTP 或文件写能力，防止 Agent Spec 获得越权能力。
     */
    @Test
    void exposesOnlyExplicitCurationBusinessTools() {
        List<String> names = callbacks(mock(AgentRunMapper.class), mock(ProjectQaToolService.class)).stream()
                .map(value -> value.getToolDefinition().name()).toList();

        assertThat(names).containsExactly(
                "conflict_record", "draft_create", "draft_diff", "draft_read", "draft_update",
                "evidence_read", "knowledge_gap_record", "knowledge_read", "knowledge_search");
        assertThat(names).doesNotContain("publish", "shell", "http", "write_file");
        System.out.printf("测试证据：场景=知识Tool允许集，Tool数=%d，发布/Shell/HTTP/文件写=0%n", names.size());
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
        ObjectProvider<KnowledgeDraftService> provider = mock(ObjectProvider.class);
        KnowledgeDraftService drafts = mock(KnowledgeDraftService.class);
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(drafts);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        when(evidence.findByRunId(61L)).thenReturn(List.of());
        when(messages.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        ToolCallback update = new KnowledgeCurationToolCallbacks(
                mock(ProjectQaToolService.class), evidence, provider, runs, messages, Clock.systemUTC())
                .callbacks().stream().filter(value -> value.getToolDefinition().name().equals("draft_update"))
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

    @SuppressWarnings("unchecked")
    private List<ToolCallback> callbacks(AgentRunMapper runs, ProjectQaToolService knowledge) {
        ObjectProvider<KnowledgeDraftService> drafts = mock(ObjectProvider.class);
        return new KnowledgeCurationToolCallbacks(
                knowledge, mock(AgentEvidenceService.class), drafts, runs,
                mock(KnowledgeTaskMessageMapper.class), Clock.systemUTC()).callbacks();
    }
}
