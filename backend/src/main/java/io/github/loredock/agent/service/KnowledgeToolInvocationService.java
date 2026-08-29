package io.github.loredock.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.agent.mapper.KnowledgeToolInvocationMapper;
import io.github.loredock.agent.model.entity.KnowledgeToolInvocationEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 按模型 toolCallId 聚合公开 Tool 调用。只保存模型实际提交和收到的业务文本，
 * ToolContext 与服务端权限字段从不进入该边界。
 */
@Service
public class KnowledgeToolInvocationService {

    private static final int MAX_ARGUMENT_CODE_POINTS = 12_000;
    private static final int MAX_RESULT_CODE_POINTS = 12_000;
    private final KnowledgeToolInvocationMapper invocations;
    private final KnowledgeTaskEventService taskEvents;

    public KnowledgeToolInvocationService(
            KnowledgeToolInvocationMapper invocations,
            KnowledgeTaskEventService taskEvents
    ) {
        this.invocations = invocations;
        this.taskEvents = taskEvents;
    }

    @Transactional
    public KnowledgeToolInvocationEntity start(
            Long conversationId,
            Long runId,
            String toolCallId,
            String toolName,
            String agentNode,
            String purpose,
            String arguments,
            Instant startedAt
    ) {
        KnowledgeToolInvocationEntity existing = find(runId, toolCallId);
        if (existing != null) {
            return existing;
        }
        BoundedText bounded = bounded(arguments, MAX_ARGUMENT_CODE_POINTS);
        int sequence = Math.toIntExact(invocations.selectCount(
                Wrappers.<KnowledgeToolInvocationEntity>lambdaQuery()
                        .eq(KnowledgeToolInvocationEntity::getRunId, runId))) + 1;
        KnowledgeToolInvocationEntity entity = KnowledgeToolInvocationEntity.builder()
                .conversationId(conversationId)
                .runId(runId)
                .toolCallId(required(toolCallId, 128))
                .sequence(sequence)
                .toolName(required(toolName, 128))
                .agentNode(agentNode)
                .purpose(required(purpose, 500))
                .argumentsText(bounded.text())
                .status("STARTED")
                .argumentsTruncated(bounded.truncated())
                .resultTruncated(false)
                .startedAt(startedAt)
                .build();
        invocations.insert(entity);
        taskEvents.append(conversationId, runId, "TOOL_UPDATED", entity.getId(), startedAt);
        return entity;
    }

    @Transactional
    public void finish(
            Long conversationId,
            Long runId,
            String toolCallId,
            String result,
            String summary,
            boolean failed,
            Instant finishedAt
    ) {
        KnowledgeToolInvocationEntity entity = find(runId, toolCallId);
        if (entity == null) {
            throw new IllegalStateException("Tool Invocation 开始事实不存在");
        }
        BoundedText bounded = bounded(result, MAX_RESULT_CODE_POINTS);
        String status = failed ? "FAILED" : "COMPLETED";
        invocations.update(null, Wrappers.<KnowledgeToolInvocationEntity>lambdaUpdate()
                .set(KnowledgeToolInvocationEntity::getResultText, bounded.text())
                .set(KnowledgeToolInvocationEntity::getResultSummary, optional(summary, 1000))
                .set(KnowledgeToolInvocationEntity::getErrorText, failed ? optional(result, 2000) : null)
                .set(KnowledgeToolInvocationEntity::getStatus, status)
                .set(KnowledgeToolInvocationEntity::getResultTruncated, bounded.truncated())
                .set(KnowledgeToolInvocationEntity::getFinishedAt, finishedAt)
                .set(KnowledgeToolInvocationEntity::getDurationMillis,
                        Duration.between(entity.getStartedAt(), finishedAt).toMillis())
                .eq(KnowledgeToolInvocationEntity::getId, entity.getId()));
        taskEvents.append(conversationId, runId, "TOOL_UPDATED", entity.getId(), finishedAt);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeToolInvocationEntity> list(Long conversationId) {
        return invocations.selectList(Wrappers.<KnowledgeToolInvocationEntity>lambdaQuery()
                .eq(KnowledgeToolInvocationEntity::getConversationId, conversationId)
                .orderByAsc(KnowledgeToolInvocationEntity::getRunId)
                .orderByAsc(KnowledgeToolInvocationEntity::getSequence));
    }

    private KnowledgeToolInvocationEntity find(Long runId, String toolCallId) {
        return invocations.selectOne(Wrappers.<KnowledgeToolInvocationEntity>lambdaQuery()
                .eq(KnowledgeToolInvocationEntity::getRunId, runId)
                .eq(KnowledgeToolInvocationEntity::getToolCallId, toolCallId));
    }

    private BoundedText bounded(String value, int maximum) {
        String text = value == null ? "" : value;
        int count = text.codePointCount(0, text.length());
        return count <= maximum
                ? new BoundedText(text, false)
                : new BoundedText(text.substring(0, text.offsetByCodePoints(0, maximum)), true);
    }

    private String required(String value, int maximum) {
        String normalized = optional(value, maximum);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Tool Invocation 必填文本为空");
        }
        return normalized;
    }

    private String optional(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        int count = normalized.codePointCount(0, normalized.length());
        return count <= maximum
                ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, maximum));
    }

    private record BoundedText(String text, boolean truncated) {
    }
}
