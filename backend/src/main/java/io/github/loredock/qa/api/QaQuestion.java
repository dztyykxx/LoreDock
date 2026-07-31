package io.github.loredock.qa.api;

import java.time.Instant;
import java.util.List;

/**
 * QA 模块公开的问答事实；同时服务 Web 展示与 Feedback 关联，不暴露请求摘要、模型配置或证据正文。
 *
 * @param questionId 问答标识
 * @param runId Agent 运行标识
 * @param scope 固定项目与分支范围
 * @param createdAt 创建时间
 * @param status 运行状态
 * @param resultType 回答或拒答
 * @param trustState 页面可信状态
 * @param answerBasis 回答依据
 * @param refusalReason 拒答原因
 * @param errorCode 运行失败码
 * @param resultText 可信结果正文
 * @param stepCount 执行步骤数
 * @param modelCallCount 模型调用数
 * @param finishedAt 终态时间
 * @param messages 已持久化的用户与助手消息
 * @param citations 已校验公开引用
 */
public record QaQuestion(
        Long questionId,
        Long runId,
        Scope scope,
        Instant createdAt,
        Status status,
        ResultType resultType,
        TrustState trustState,
        AnswerBasis answerBasis,
        RefusalReason refusalReason,
        ErrorCode errorCode,
        String resultText,
        int stepCount,
        int modelCallCount,
        Instant finishedAt,
        List<Message> messages,
        List<Citation> citations
) {
    public QaQuestion {
        messages = messages == null ? List.of() : List.copyOf(messages);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 运行生命周期。 */
    public enum Status { ACCEPTED, RUNNING, COMPLETED, FAILED, TERMINATED }

    /** 可信结果类型。 */
    public enum ResultType { ANSWER, REFUSAL }

    /** 页面可信状态。 */
    public enum TrustState { IN_PROGRESS, RELIABLE_ANSWER, SOURCE_CONFLICT, INSUFFICIENT_EVIDENCE, FAILED }

    /** 回答依据。 */
    public enum AnswerBasis { BUSINESS_RULE, CURRENT_IMPLEMENTATION, MIXED }

    /** 可信拒答原因。 */
    public enum RefusalReason {
        INSUFFICIENT_EVIDENCE, CODE_SNAPSHOT_NOT_INDEXED, OUT_OF_SCOPE, SOURCE_CONFLICT,
        AGENT_CITATION_INVALID, OUTPUT_POLICY_VIOLATION
    }

    /** Web 与 Feedback 可稳定识别的运行失败原因。 */
    public enum ErrorCode {
        AGENT_RUN_IDEMPOTENCY_CONFLICT, AGENT_SKILL_UNAVAILABLE, AGENT_DISABLED,
        AGENT_RUNTIME_UNAVAILABLE, AGENT_RUNTIME_BUSY, AGENT_MODEL_UNAVAILABLE,
        AGENT_MODEL_RESPONSE_INVALID, AGENT_TOOL_NOT_ALLOWED, AGENT_TOOL_SCOPE_VIOLATION,
        AGENT_EVIDENCE_VERSION_CHANGED, AGENT_STEP_LIMIT_EXCEEDED,
        AGENT_MODEL_CALL_LIMIT_EXCEEDED, AGENT_RUN_TIMEOUT, AGENT_RUN_INTERRUPTED,
        AGENT_CITATION_INVALID, AGENT_INTERNAL_ERROR
    }

    /** 引用来源类型。 */
    public enum EvidenceSourceType { KNOWLEDGE, CODE }

    /** 消息角色。 */
    public enum MessageRole { USER, ASSISTANT }

    /**
     * @param projectId 项目数据库标识
     * @param projectIdentifier 项目标识
     * @param branchId 分支数据库标识
     * @param branch 分支名
     * @param commit 固定 commit
     * @param codeSnapshotAvailable 是否有活动代码快照
     */
    public record Scope(
            Long projectId,
            String projectIdentifier,
            Long branchId,
            String branch,
            String commit,
            boolean codeSnapshotAvailable
    ) {
    }

    /**
     * @param id 消息标识
     * @param role 用户或助手
     * @param content 消息正文
     * @param resultType 助手终态类型
     * @param refusalReason 助手拒答原因
     * @param createdAt 创建时间
     */
    public record Message(
            Long id,
            MessageRole role,
            String content,
            ResultType resultType,
            RefusalReason refusalReason,
            Instant createdAt
    ) {
    }

    /**
     * @param evidenceId Agent 证据标识
     * @param documentId 知识文档标识
     * @param snapshotId 代码快照标识
     * @param order 引用顺序
     * @param sourceType 来源类型
     * @param projectIdentifier 项目标识
     * @param branch 分支
     * @param commit commit
     * @param repositoryPath 仓库相对路径
     * @param title 来源标题
     * @param sourceUpdatedAt 来源更新时间
     * @param scopeType 知识范围
     * @param knowledgeSourceType 知识来源
     * @param wikiUrl Wiki 地址
     * @param originalFilename 原始文件名
     */
    public record Citation(
            Long evidenceId,
            Long documentId,
            Long snapshotId,
            int order,
            EvidenceSourceType sourceType,
            String projectIdentifier,
            String branch,
            String commit,
            String repositoryPath,
            String title,
            Instant sourceUpdatedAt,
            String scopeType,
            String knowledgeSourceType,
            String wikiUrl,
            String originalFilename
    ) {
    }
}
