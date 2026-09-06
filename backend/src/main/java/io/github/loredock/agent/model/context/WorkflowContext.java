package io.github.loredock.agent.model.context;

import java.util.List;

/**
 * 当前轮工作上下文（设计文档 §4.1）：由 Graph State 结构化键投影，
 * 正文一律以稳定引用表达，正文读取仍由受限业务 Tool 承担。
 */
public record WorkflowContext(
        List<SupportedFact> facts,
        List<UnresolvedQuestion> unresolvedQuestions,
        List<SourceReference> sourceRefs,
        List<DraftReference> drafts,
        DraftInstruction draftInstruction,
        ReviewTarget reviewTarget,
        RetryContext retry,
        List<ReviewFinding> findings,
        CurationOutcome curationOutcome
) {

    /** 兼容非汇总阶段的最小工作上下文构造。 */
    public WorkflowContext(
            List<SupportedFact> facts,
            List<UnresolvedQuestion> unresolvedQuestions,
            List<SourceReference> sourceRefs,
            List<DraftReference> drafts,
            DraftInstruction draftInstruction,
            ReviewTarget reviewTarget,
            RetryContext retry,
            List<ReviewFinding> findings
    ) {
        this(facts, unresolvedQuestions, sourceRefs, drafts, draftInstruction, reviewTarget, retry, findings, null);
    }

    /** 本轮审查发现（仅 REVISE 返工入口的 Drafter 接收当前轮发现；旧轮结论不继承）。 */
    public record ReviewFinding(String code, String draftId, String description) {
    }

    /** 已支持事实：serviceId 与来源引用（draft/knowledge 前缀）。 */
    public record SupportedFact(String statement, List<String> sourceRefs) {
    }

    /** 未解决问题：id 只在会话内可指代，不作为事实来源。 */
    public record UnresolvedQuestion(String id, String question) {
    }

    /** 稳定引用：type 取 EVIDENCE / KNOWLEDGE / DRAFT，id 为业务对象标识。 */
    public record SourceReference(String type, String id) {
    }

    /** 草稿引用：只表达 draftId + revision，正文由工具重读。 */
    public record DraftReference(String draftId, int revision) {
    }

    /** 写入要求（来自调度决策）：目标目录与任务摘要。 */
    public record DraftInstruction(String targetDirectory, String instruction) {
    }

    /** 待审目标：草稿引用 + 审查用途说明。 */
    public record ReviewTarget(String draftId, int revision) {
    }

    /** 修复回路上下文：最后一次校验失败的有界摘要、节点与原阶段。 */
    public record RetryContext(int attempt, String lastValidatedNode, String validationError, String stage) {
        /** 兼容旧调用方：没有阶段信息时由组装层按未知阶段处理。 */
        public RetryContext(int attempt, String lastValidatedNode, String validationError) {
            this(attempt, lastValidatedNode, validationError, null);
        }
    }

    /** 完整整理结束时交给最终汇报 Agent 的结构化结果摘要。 */
    public record CurationOutcome(
            String issueType,
            String coordinatorAction,
            String coordinatorReason,
            String coordinatorQuestion,
            String coordinatorSummary,
            String draftStatus,
            String reviewVerdict
    ) {
    }

    public WorkflowContext {
        facts = facts == null ? List.of() : List.copyOf(facts);
        unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
