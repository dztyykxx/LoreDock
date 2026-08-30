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
        List<ReviewFinding> findings
) {

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

    /** 修复回路上下文：最后一次校验失败的有界摘要与节点。 */
    public record RetryContext(int attempt, String lastValidatedNode, String validationError) {
    }

    public WorkflowContext {
        facts = facts == null ? List.of() : List.copyOf(facts);
        unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
