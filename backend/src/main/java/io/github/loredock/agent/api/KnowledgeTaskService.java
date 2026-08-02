package io.github.loredock.agent.api;

import java.time.Instant;
import java.util.List;

/**
 * 对话式知识任务的跨模块契约。
 *
 * <p>会话负责串联可见消息、独立 Agent run 和当前草稿指针；框架 Checkpoint 仍由
 * Spring AI Alibaba 管理。本契约中的暂停状态只是对真实 interrupt 与 Checkpoint 的页面投影，
 * 不能被实现为第二套 Agent 状态机。</p>
 */
public interface KnowledgeTaskService {

    /**
     * 通过人工或系统触发的统一入口创建知识任务。
     *
     * @param request 已固定项目、Skill、触发原因和幂等键的请求
     * @return 新建或按同一幂等请求复用的会话
     * @throws KnowledgeTaskRequestException 请求冲突、定义无效或 Tool 越权
     */
    KnowledgeTask start(StartRequest request);

    /**
     * 读取操作者可见的完整任务快照。
     *
     * @param conversationId 知识任务会话标识
     * @param operatorId 当前已认证操作者
     * @return 会话、消息和各轮运行的已提交事实
     * @throws KnowledgeTaskRequestException 会话不存在或当前操作者不可见
     */
    KnowledgeTask get(Long conversationId, String operatorId);

    /**
     * 请求在当前模型或 Tool 步骤安全结束后暂停。
     *
     * @param request 当前运行和操作者
     * @return 已投影为请求暂停或已经等待人工的运行
     * @throws KnowledgeTaskRequestException 运行不存在、越权或当前状态不可暂停
     */
    KnowledgeTaskRun requestPause(PauseRequest request);

    /**
     * 在真实等待状态提交指导并恢复同一个 run/threadId。
     *
     * @param request 用户指导和当前运行
     * @return 恢复后的同一运行
     * @throws KnowledgeTaskRequestException 没有可靠 Checkpoint、越权或运行并非等待人工
     */
    KnowledgeTaskRun resume(ResumeRequest request);

    /**
     * 在一轮正常完成后追加意见，并从当前草稿修订创建新的 run。
     *
     * @param request 会话、用户意见和幂等键
     * @return 新创建或幂等复用的独立运行
     * @throws KnowledgeTaskRequestException 会话不可继续、越权或幂等请求冲突
     */
    KnowledgeTaskRun continueTask(ContinueRequest request);

    /**
     * @param idempotencyKey 当前触发范围内的稳定幂等键
     * @param operatorId 已认证操作者；系统触发使用服务端固定的系统主体
     * @param projectIdentifier 已启用项目标识
     * @param triggerType 人工或系统触发
     * @param triggerReason 作为首条系统消息保存的有限触发原因
     * @param targetSkill 目标本地 Skill，当前知识整理固定为 knowledge_curator
     * @param goal 本次知识任务目标
     */
    record StartRequest(
            String idempotencyKey,
            String operatorId,
            String projectIdentifier,
            TriggerType triggerType,
            String triggerReason,
            String targetSkill,
            String goal
    ) {
    }

    /**
     * @param runId 当前运行标识
     * @param operatorId 已认证管理员
     */
    record PauseRequest(Long runId, String operatorId) {
    }

    /**
     * @param runId 等待人工的运行标识
     * @param operatorId 已认证管理员
     * @param guidance 用户追加的整理方向
     */
    record ResumeRequest(Long runId, String operatorId, String guidance) {
    }

    /**
     * @param conversationId 已完成一轮的知识任务会话
     * @param operatorId 已认证管理员
     * @param idempotencyKey 新一轮运行的幂等键
     * @param guidance 基于当前草稿继续调整的意见
     */
    record ContinueRequest(Long conversationId, String operatorId, String idempotencyKey, String guidance) {
    }

    /**
     * @param conversationId 会话标识
     * @param projectIdentifier 固定项目范围
     * @param triggerType 首次触发类型
     * @param targetSkill 目标 Skill
     * @param goal 任务目标
     * @param currentDraftId 当前草稿标识；Agent 尚未创建草稿时为空
     * @param currentDraftRevision 当前草稿修订；尚无草稿时为空
     * @param messages 已提交的可见消息；不包含隐藏提示、思维链或 Tool 原文
     * @param runs 会话内相互独立的运行
     * @param events 从真实运行事件投影的字段白名单过程；不包含模型思维链或 Tool 原始返回
     * @param createdAt 会话创建时间
     * @param updatedAt 最后可见变化时间
     */
    record KnowledgeTask(
            Long conversationId,
            String projectIdentifier,
            TriggerType triggerType,
            String targetSkill,
            String goal,
            Long currentDraftId,
            Long currentDraftRevision,
            List<KnowledgeTaskMessage> messages,
            List<KnowledgeTaskRun> runs,
            List<AgentEvent> events,
            Instant createdAt,
            Instant updatedAt
    ) {
        public KnowledgeTask {
            messages = messages == null ? List.of() : List.copyOf(messages);
            runs = runs == null ? List.of() : List.copyOf(runs);
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    /**
     * @param messageId 消息标识
     * @param runId 关联运行；首条系统触发消息可在 run 创建前为空
     * @param role 可见消息角色
     * @param subjectName 子 Agent 或 Tool 名称；普通系统/用户消息为空
     * @param content 经过长度和公开策略校验的正文
     * @param createdAt 消息提交时间
     */
    record KnowledgeTaskMessage(
            Long messageId,
            Long runId,
            MessageRole role,
            String subjectName,
            String content,
            Instant createdAt
    ) {
    }

    /**
     * @param runId 运行标识
     * @param conversationId 所属会话
     * @param threadId 恢复期间保持不变的 Graph threadId
     * @param status 框架事实的安全页面投影
     * @param definition 本运行固定的 Skill、Agent Spec、模型和 Tool 摘要
     * @param checkpointSavedAt 最近可读取 Checkpoint 时间；没有可靠 Checkpoint 时为空
     * @param stepCount 已完成步骤数
     * @param modelCallCount 已完成模型调用数
     * @param toolCallCount 已完成 Tool 调用数
     * @param errorCode 稳定失败码
     * @param acceptedAt 受理时间
     * @param startedAt 开始时间
     * @param finishedAt 终态时间
     */
    record KnowledgeTaskRun(
            Long runId,
            Long conversationId,
            String threadId,
            RunStatus status,
            RuntimeDefinition definition,
            Instant checkpointSavedAt,
            int stepCount,
            int modelCallCount,
            int toolCallCount,
            String errorCode,
            Instant acceptedAt,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    /**
     * @param skillName 本运行 Skill 名称
     * @param skillDigest 本运行读取内容的 SHA-256 摘要
     * @param agentSpecDigest 本运行全部预定义子 Agent Spec 的稳定摘要
     * @param modelName 服务端固定模型名
     * @param toolNames 排序后的显式允许 Tool 名称
     */
    record RuntimeDefinition(
            String skillName,
            String skillDigest,
            String agentSpecDigest,
            String modelName,
            List<String> toolNames
    ) {
        public RuntimeDefinition {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }
    }

    /** 知识任务首次触发来源。 */
    enum TriggerType { MANUAL, SYSTEM }

    /** 可进入对话时间线的公开主体；Tool 原始返回仍只能作为安全运行事件保存。 */
    enum MessageRole { SYSTEM_TRIGGER, USER, COORDINATOR_AGENT, SUB_AGENT, TOOL }

    /**
     * 框架运行事实的页面投影。
     *
     * <p>`PAUSE_REQUESTED` 只表示已请求在安全边界暂停；只有已提交且可读取 Checkpoint 时才能进入
     * `WAITING_FOR_USER`。恢复指导继续同一个 run，正常完成后追加意见则创建新 run。</p>
     */
    enum RunStatus {
        ACCEPTED, RUNNING, PAUSE_REQUESTED, WAITING_FOR_USER, COMPLETED, FAILED, TERMINATED, CANCELLED;

        /** @return 是否已经进入不可变终态 */
        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == TERMINATED || this == CANCELLED;
        }

        /** @return 是否已经有可靠暂停点并允许提交指导 */
        public boolean acceptsGuidance() {
            return this == WAITING_FOR_USER;
        }
    }
}
