package io.github.loredock.agent.service;

import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.memory.api.MemoryCandidate;
import io.github.loredock.memory.api.MemoryFull;
import io.github.loredock.memory.api.MemoryRelevant;
import io.github.loredock.memory.api.MemoryRelevantQuery;
import io.github.loredock.memory.api.MemoryService;
import io.github.loredock.memory.api.MemoryWriteInput;
import io.github.loredock.memory.api.MemoryWriteVerdict;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 用户记忆允许集：仅注册到主 Agent（专家白名单不变，专家不感知记忆层）。
 *
 * <p>前缀稳定性边界：本轮上下文中的记忆摘要快照是 run 固定值，本工具的读写
 * （频次变化、新增记忆）不得改变当前 run 前缀——只影响后续 run 的预载。</p>
 *
 * <p>调用方（模型输入）只包含业务参数；操作者、项目、会话和 run 必须来自服务端
 * ToolContext，并在工具内回查 {@code agent_run} 固定范围（taskType=knowledge_curation、
 * RUNNING、会话与项目一致），上下文与运行状态不符时一律拒绝。</p>
 */
@Component
public class MemoryTools {

    /** 检索参数限长：与 {@link MemoryRelevantQuery} 的每条 ≤100 码点约束一致。 */
    static final int QUERY_WORD_CODE_POINTS = 100;

    private final MemoryService memories;
    private final AgentRunMapper runs;

    /**
     * @param memories 记忆契约（跨模块只依赖 {@code memory.api}）
     * @param runs 运行固定范围事实
     */
    public MemoryTools(MemoryService memories, AgentRunMapper runs) {
        this.memories = memories;
        this.runs = runs;
    }

    /** @return 固定范围内与查询相关的记忆摘要（无正文，全文用 memory_read） */
    @Tool(name = "memory_search", description = "检索当前会话范围（本项目+通用）内与查询相关的记忆摘要")
    public List<MemoryRelevant> memorySearch(
            @ToolParam(description = "要检索的记忆问题或主题") String query,
            @ToolParam(required = false, description = "期望返回数量，最终仍受服务端上限约束") Integer limit,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        return memories.listRelevant(new MemoryRelevantQuery(
                List.of(bounded(query, QUERY_WORD_CODE_POINTS)), scope.projectId(), defaultLimit(limit)));
    }

    /** @return 指定记忆的完整正文（有界；越权或不存在即拒绝） */
    @Tool(name = "memory_read", description = "读取记忆全文：用注入块行尾编号或 memory_search 返回的编号定位")
    public MemoryFull memoryRead(
            @ToolParam(description = "记忆编号（注入块行尾编号或 memory_search 返回值中的 id）") Long memoryId,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        if (memoryId == null || memoryId <= 0) {
            throw new IllegalArgumentException("记忆编号无效");
        }
        return memories.loadFull(memoryId, scope.projectId());
    }

    /**
     * 提炼用户偏好候选为记忆：范围由会话自身决定（会话挂项目→PROJECT，否则 GLOBAL），
     * 写入前由判断链（值得写/语义重复/冲突仍写/预算）裁决，逐条返回结论。
     *
     * @param candidates 候选列表（1~3 条）
     * @return 逐条写入结论（含 summary，无正文）
     */
    @Tool(name = "memory_write", description = "把用户在这轮对话表达的长期偏好提炼成记忆；范围由会话自身决定，服务端写入前做值得写/重复/冲突判断")
    public List<MemoryWriteVerdict> memoryWrite(
            @ToolParam(description = "待提炼的偏好候选（每条：title/content/category/summary 见字段说明）") List<MemoryCandidate> candidates,
            ToolContext context
    ) {
        ToolScope scope = scope(context);
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("memory_write 至少需要一条候选");
        }
        return memories.acceptWrite(new MemoryWriteInput(
                scope.projectId(), scope.runId(), scope.conversationId(),
                scope.operatorId(), List.copyOf(candidates)));
    }

    /** 由服务端校验 ToolContext 并回查 run 固定范围；projectId 为空表示 GLOBAL 侧会话。 */
    private ToolScope scope(ToolContext context) {
        if (context == null) {
            throw new IllegalArgumentException("记忆工具缺少服务端固定上下文");
        }
        Map<String, Object> values = context.getContext();
        ToolScope scope = new ToolScope(
                text(values, "operatorId"), text(values, "projectIdentifier"),
                number(values, "conversationId"), number(values, "runId"), optionalNumber(values, "projectId"));
        AgentRunEntity run = runs.selectById(scope.runId());
        if (run == null || !scope.operatorId().equals(run.getOperatorId())
                || !scope.projectIdentifier().equals(run.getProjectIdentifier())
                || !scope.conversationId().equals(run.getKnowledgeTaskConversationId())
                || !"knowledge_curation".equals(run.getTaskType())
                || !"RUNNING".equals(run.getStatus())
                || !Objects.equals(scope.projectId(), run.getProjectId())) {
            throw new IllegalArgumentException("记忆工具上下文与运行固定范围不一致");
        }
        return scope;
    }

    private String text(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("记忆工具上下文缺少 " + name);
        }
        return text;
    }

    private Long number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalArgumentException("记忆工具上下文缺少 " + name);
        }
        return number.longValue();
    }

    /** 可空数值：缺省表示 GLOBAL 侧会话（run.projectId 为空时也缺省才能通过一致性校验）。 */
    private Long optionalNumber(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalArgumentException("记忆工具上下文缺失 " + name);
        }
        return number.longValue();
    }

    private int defaultLimit(Integer value) {
        return value == null ? 20 : value;
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    /** ToolContext 解析后的固定范围；projectId 为空表示 GLOBAL 侧会话。 */
    private record ToolScope(
            String operatorId, String projectIdentifier, Long conversationId, Long runId, Long projectId
    ) {
    }
}
