package io.github.loredock.agent.api;

import java.time.Instant;
import java.util.List;

/**
 * QA 可见的 Agent 运行事实；只包含页面终态、范围、用量和引用所需的稳定字段。
 *
 * @param runId 运行标识
 * @param status 运行状态
 * @param resultType 完成后的回答或拒答类型
 * @param answerBasis 回答依据
 * @param resultText 可信回答或拒答正文
 * @param refusalReason 拒答原因
 * @param errorCode 失败原因
 * @param scope 运行受理时固定的项目与检索范围
 * @param stepCount 实际执行步骤数
 * @param modelCallCount 实际模型调用数
 * @param acceptedAt 受理时间
 * @param startedAt 开始时间
 * @param finishedAt 终态时间
 * @param citations 已校验的公开引用
 */
public record AgentRun(
        Long runId,
        Status status,
        ResultType resultType,
        AnswerBasis answerBasis,
        String resultText,
        RefusalReason refusalReason,
        ErrorCode errorCode,
        Scope scope,
        int stepCount,
        int modelCallCount,
        Instant acceptedAt,
        Instant startedAt,
        Instant finishedAt,
        List<Citation> citations
) {
    public AgentRun {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Agent 运行的单调生命周期状态。 */
    public enum Status {
        ACCEPTED, RUNNING, COMPLETED, FAILED, TERMINATED;

        /** @return 是否已进入不可变终态 */
        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == TERMINATED;
        }
    }

    /** 已完成运行的可信结果类型。 */
    public enum ResultType { ANSWER, REFUSAL }

    /** 回答声明使用的事实来源。 */
    public enum AnswerBasis { BUSINESS_RULE, CURRENT_IMPLEMENTATION, MIXED }

    /** 可信拒答原因。 */
    public enum RefusalReason {
        INSUFFICIENT_EVIDENCE, CODE_SNAPSHOT_NOT_INDEXED, OUT_OF_SCOPE, SOURCE_CONFLICT,
        AGENT_CITATION_INVALID, OUTPUT_POLICY_VIOLATION
    }

    /** QA 可稳定识别的运行失败原因。 */
    public enum ErrorCode {
        AGENT_RUN_IDEMPOTENCY_CONFLICT, AGENT_SKILL_UNAVAILABLE, AGENT_DISABLED,
        AGENT_RUNTIME_UNAVAILABLE, AGENT_RUNTIME_BUSY, AGENT_MODEL_UNAVAILABLE,
        AGENT_MODEL_RESPONSE_INVALID, AGENT_TOOL_NOT_ALLOWED, AGENT_TOOL_SCOPE_VIOLATION,
        AGENT_EVIDENCE_VERSION_CHANGED, AGENT_STEP_LIMIT_EXCEEDED,
        AGENT_MODEL_CALL_LIMIT_EXCEEDED, AGENT_RUN_TIMEOUT, AGENT_RUN_INTERRUPTED,
        AGENT_CITATION_INVALID, AGENT_INTERNAL_ERROR
    }

    /** 公开引用来源类型。 */
    public enum EvidenceSourceType { KNOWLEDGE, CODE }

    /**
     * @param projectId 项目数据库标识
     * @param projectIdentifier 项目业务标识
     * @param branchId 分支数据库标识
     * @param branch 实际分支
     * @param snapshotId 固定代码快照；未索引时为空
     * @param commit 固定 commit；未索引时为空
     * @param knowledgeGenerationId 固定知识索引版本
     */
    public record Scope(
            Long projectId,
            String projectIdentifier,
            Long branchId,
            String branch,
            Long snapshotId,
            String commit,
            Long knowledgeGenerationId
    ) {
        /** @return 该运行是否固定了可用代码快照 */
        public boolean hasCodeSnapshot() {
            return snapshotId != null && commit != null;
        }
    }

    /**
     * @param evidenceId 证据标识
     * @param sourceType 证据来源类型
     * @param documentId 知识文档标识
     * @param snapshotId 代码快照标识
     * @param projectIdentifier 项目标识
     * @param branch 分支
     * @param commit commit
     * @param repositoryPath 仓库相对路径
     * @param title 来源标题
     * @param sourceUpdatedAt 来源更新时间
     * @param order 引用顺序
     * @param sourceMetadata 知识来源附属信息
     */
    public record Citation(
            Long evidenceId,
            EvidenceSourceType sourceType,
            Long documentId,
            Long snapshotId,
            String projectIdentifier,
            String branch,
            String commit,
            String repositoryPath,
            String title,
            Instant sourceUpdatedAt,
            int order,
            SourceMetadata sourceMetadata
    ) {
    }

    /**
     * @param schemaVersion 来源元数据版本；历史未知时为空
     * @param scopeType 知识范围类型
     * @param knowledgeSourceType 知识来源类型
     * @param wikiUrl Wiki 地址
     * @param originalFilename 原始文件名
     */
    public record SourceMetadata(
            String schemaVersion,
            String scopeType,
            String knowledgeSourceType,
            String wikiUrl,
            String originalFilename
    ) {
    }
}
