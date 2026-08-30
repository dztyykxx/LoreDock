package io.github.loredock.agent.model.result;

import java.util.List;
import java.util.Objects;

/**
 * 知识整理多 Agent Graph 的四种结构化输出与 Graph State 路由字段。
 *
 * <p>本文件是唯一的知识整理图结果契约，使用嵌套 record 表达调度、检索、草稿和审查四种结果，
 * 避免为每个字段建立多层 DTO。四个专家 {@code ReactAgent} 通过框架 {@code outputType} 约束最终
 * 输出为这里的记录，路由节点再通过 Jackson 解析最终 {@code AssistantMessage.text} 得到稳定路由依据。
 * 字段全部使用 camelCase，与 Java 内部契约一致，不额外维护 snake_case 映射（见设计文档“路由和解析规则”）。
 * </p>
 */
public final class KnowledgeCurationGraphResult {

    private KnowledgeCurationGraphResult() {
    }

    /** 调度 Agent 的 stage：任务准备、动作决策、最终汇总。 */
    public enum Stage {
        START,
        DECIDE,
        FINISH
    }

    /** 调度 Agent 在单个 stage 内允许选择的路由动作。 */
    public enum CoordinatorAction {
        CHAT,
        RETRIEVE,
        DRAFT,
        ASK_USER,
        NO_CHANGE,
        END
    }

    /** 检索 Agent 对候选材料与现有知识的关系判断。 */
    public enum IssueType {
        DUPLICATE,
        CONFLICT,
        MISSING,
        NONE
    }

    /** 检索事实的证据充分性；只有 SUPPORTED 的事实才允许写入草稿。 */
    public enum FactSupport {
        SUPPORTED,
        CONFLICTED,
        INSUFFICIENT
    }

    /** 检索事实的来源类型。 */
    public enum SourceKind {
        EVIDENCE,
        SELECTED_DRAFT,
        USER_MESSAGE
    }

    /** 草稿 Agent 的写入终态。 */
    public enum DraftStatus {
        WRITTEN,
        BLOCKED
    }

    /** 草稿写入操作类型。 */
    public enum DraftOperation {
        ADD,
        MODIFY
    }

    /** 审查 Agent 的结论。 */
    public enum ReviewVerdict {
        PASS,
        REVISE,
        ASK_USER
    }

    /** 审查发现的问题代码，供路由与页面做稳定判断。 */
    public enum FindingCode {
        UNSUPPORTED_CLAIM,
        USER_INTENT_MISMATCH,
        UNRESOLVED_CONFLICT,
        DOCUMENT_BOUNDARY
    }

    /** 主 Agent 在单个轮次内的意图动作：直接回答、组合专家后结束、交由完整整理流程。 */
    public enum MainAction {
        CHAT,
        TURN_DONE,
        FULL_CURATION
    }

    /** 主 Agent（会话级调度者）的结构化输出。 */
    public record MainTurnResult(
            MainAction action,
            String summary,
            List<String> expertCalls
    ) {
        public MainTurnResult {
            action = action == null ? MainAction.CHAT : action;
            expertCalls = expertCalls == null ? List.of() : List.copyOf(expertCalls);
        }
    }

    /** 调度 Agent 的结构化输出。 */
    public record CoordinatorResult(
            Stage stage,
            CoordinatorAction action,
            String reason,
            String draftInstruction,
            String question,
            String summary
    ) {
        public CoordinatorResult {
            action = action == null ? CoordinatorAction.END : action;
        }
    }

    /** 检索事实的来源引用。 */
    public record SourceRef(SourceKind type, Long id) {
    }

    /** 检索 Agent 提交的一条证据事实。 */
    public record Fact(String statement, FactSupport support, List<SourceRef> sourceRefs) {
        public Fact {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }

    /** 检索 Agent 的结构化结果。 */
    public record RetrievalResult(
            IssueType issueType,
            Long candidateTargetDocumentId,
            List<Fact> facts,
            List<String> unresolvedQuestions,
            String summary
    ) {
        public RetrievalResult {
            facts = facts == null ? List.of() : List.copyOf(facts);
            unresolvedQuestions = unresolvedQuestions == null ? List.of()
                    : List.copyOf(unresolvedQuestions);
        }
    }

    /** 草稿 Agent 对单个文档写入的回执。 */
    public record DraftEntry(Long draftId, Integer revision, DraftOperation operation) {
    }

    /** 草稿 Agent 的结构化结果。 */
    public record DraftResult(
            DraftStatus status,
            List<DraftEntry> drafts,
            String question,
            String summary
    ) {
        public DraftResult {
            drafts = drafts == null ? List.of() : List.copyOf(drafts);
        }
    }

    /** 审查 Agent 对某个文档的具体问题。 */
    public record Finding(FindingCode code, Long draftId, String description, String suggestion) {
        public Finding {
            description = Objects.requireNonNullElse(description, "");
            suggestion = Objects.requireNonNullElse(suggestion, "");
        }
    }

    /** 审查 Agent 的结构化结果。 */
    public record ReviewResult(
            ReviewVerdict verdict,
            List<DraftEntry> reviewedDrafts,
            List<Finding> findings,
            String question,
            String summary
    ) {
        public ReviewResult {
            reviewedDrafts = reviewedDrafts == null ? List.of() : List.copyOf(reviewedDrafts);
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }
}
