package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryCandidate;
import io.github.loredock.memory.api.MemoryRelevant;
import io.github.loredock.memory.api.MemoryRelevantQuery;
import io.github.loredock.memory.api.MemoryScope;
import io.github.loredock.memory.api.MemoryService;
import io.github.loredock.memory.api.MemoryWriteInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/** 验证记忆三工具只在服务端固定运行范围内执行：run 状态不符、会话/项目不一致一律拒绝。 */
class MemoryToolsTest {

    private static final ToolContext GLOBAL_CONTEXT = new ToolContext(Map.of(
            "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L, "runId", 61L));

    /** 业务目的：run 不在 RUNNING（取消/失败终态）时模型已无资格执行记忆工具，
     *  防止被取消的模型调用仍在驱动记忆写入或频次计数。 */
    @Test
    void refusesToolWhenRunIsNotRunning() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("CANCELLED").build());
        MemoryService memories = mock(MemoryService.class);
        MemoryTools tools = new MemoryTools(memories, runs);

        assertThatThrownBy(() -> tools.memorySearch("三级标题", 5, GLOBAL_CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("记忆工具上下文与运行固定范围不一致");
        verifyNoInteractions(memories);
        System.out.println("测试证据：场景=终态run拒绝记忆工具，CANCELLED run 调用 memory_search 被拒");
    }

    /** 业务目的：ToolContext 的 projectId 必与 run.projectId 一致；不一致说明范围注入了来自模型（或过期）的值，
     *  防止 PROJECT 记忆被越权写入其他项目或读到错误范围。 */
    @Test
    void refusesToolWhenProjectScopeMismatchesRun() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas").projectId(7L)
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        MemoryService memories = mock(MemoryService.class);
        MemoryTools tools = new MemoryTools(memories, runs);
        ToolContext mismatched = new ToolContext(Map.of(
                "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L,
                "runId", 61L, "projectId", 9L));

        assertThatThrownBy(() -> tools.memoryRead(12L, mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("记忆工具上下文与运行固定范围不一致");
        verifyNoInteractions(memories);
        System.out.println("测试证据：场景=项目范围不一致拒绝记忆工具，context projectId=9 run projectId=7");
    }

    /** 业务目的：写入范围完全由 run 决定（会话挂项目→PROJECT），模型只提交候选内容，不传范围；
     *  防止模型把偏好写成其他项目或通用记忆。 */
    @Test
    void memoryWriteDerivesProjectScopeFromRun() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas").projectId(7L)
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        MemoryService memories = mock(MemoryService.class);
        when(memories.acceptWrite(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        MemoryTools tools = new MemoryTools(memories, runs);
        ToolContext projectContext = new ToolContext(Map.of(
                "operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 41L,
                "runId", 61L, "projectId", 7L));
        MemoryCandidate candidate = new MemoryCandidate("格式偏好", "正文使用三级标题",
                MemoryCategory.FORMAT, null);

        tools.memoryWrite(List.of(candidate), projectContext);

        verify(memories).acceptWrite(new MemoryWriteInput(7L, 61L, 41L, "admin", List.of(candidate)));
        System.out.println("测试证据：场景=写入范围由run决定，PROJECT run 传递 projectId=7，模型未传范围");
    }

    /** 业务目的：检索查询词与范围同样取自 run 固定事实，且查询词有界（≤100 码点）。 */
    @Test
    void memorySearchDerivesScopeAndBoundsQueryFromRun() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.selectById(61L)).thenReturn(AgentRunEntity.builder()
                .id(61L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(41L).taskType("knowledge_curation").status("RUNNING").build());
        MemoryService memories = mock(MemoryService.class);
        when(memories.listRelevant(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new MemoryRelevant(12L, MemoryScope.GLOBAL, null, null, MemoryCategory.FORMAT,
                        "三级标题", "段落组织", 2)));
        MemoryTools tools = new MemoryTools(memories, runs);

        List<MemoryRelevant> result = tools.memorySearch("正文格式偏好", 5, GLOBAL_CONTEXT);

        verify(memories).listRelevant(new MemoryRelevantQuery(List.of("正文格式偏好"), null, 5));
        assertThat(result).hasSize(1);
        System.out.println("测试证据：场景=检索范围GLOBAL，查询词有界，limit=5，命中=1");
    }
}
