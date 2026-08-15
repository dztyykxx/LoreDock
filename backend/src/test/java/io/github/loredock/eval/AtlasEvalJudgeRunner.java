package io.github.loredock.eval;

import io.github.loredock.eval.AtlasAgentEvalFixture.DocumentSpec;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasCurationEvalRunner.WorkspaceActual;
import io.github.loredock.eval.AtlasEvalJudge.CurationJudgeInput;
import io.github.loredock.eval.AtlasEvalJudge.CurationJudgement;
import io.github.loredock.eval.AtlasEvalJudge.QaJudgeInput;
import io.github.loredock.eval.AtlasEvalJudge.QaJudgement;
import io.github.loredock.eval.AtlasEvalMetrics.CurationSummary;
import io.github.loredock.eval.AtlasEvalMetrics.CurationVerdict;
import io.github.loredock.eval.AtlasEvalMetrics.QaSummary;
import io.github.loredock.eval.AtlasEvalMetrics.QaVerdict;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.eval.AtlasQaEvalRunner.RetrievalActual;
import io.github.loredock.eval.AtlasQaEvalRunner.RetrievedActual;
import io.github.loredock.eval.AgentEvalReport.CurationCaseResult;
import io.github.loredock.eval.AgentEvalReport.QaCaseResult;
import io.github.loredock.eval.AgentEvalReport.Report;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 离线评判运行器：读取已生成的评估报告，逐条调用 LLM Judge，回填判定并重算指标。
 *
 * <p>评判只读报告与数据集原文，不重跑 Agent；QA 判定填入忠实度/相关性/理由，
 * 知识整理判定以 Judge 的问题识别、动作与误写结论替换确定性近似，并计算问题识别
 * 正确率与各问题类型 F1。</p>
 */
public final class AtlasEvalJudgeRunner {

    /** 检索原文送入 Judge 的最大字符数，超出部分截断并注明，避免超大上下文。 */
    private static final int MAX_RETRIEVAL_CHARACTERS = 16000;

    private AtlasEvalJudgeRunner() {
    }

    /**
     * @param report 未评判的评估报告
     * @param data 评估数据集（提供草稿与正式文档全文）
     * @param judge LLM Judge
     * @return 已回填 Judge 判定并重算指标的评估报告
     */
    public static Report judge(Report report, EvalData data, AtlasEvalJudge judge) {
        List<QaCaseResult> judgedQa = report.qaResults().stream().map(result -> {
            QaJudgement judgement = judge.judgeQa(new QaJudgeInput(
                    result.caseId(), result.input().question(), result.expected().resultText(),
                    result.actual().resultText(), retrievalText(result.actual()),
                    result.actual().citationDocumentIds()));
            QaVerdict verdict = result.verdict();
            QaVerdict judged = new QaVerdict(verdict.caseId(), verdict.caseType(),
                    judgement.reason(), judgement.faithfulness(), judgement.relevance(),
                    verdict.completed(), verdict.resultTypeMatch(), verdict.refusalReasonMatch(),
                    verdict.answerable(), verdict.hitTop5(), verdict.top5Recall(), verdict.citationCoverage());
            return new QaCaseResult(result.caseId(), result.caseType(), result.input(),
                    result.expected(), result.actual(), judged);
        }).toList();

        Map<String, String> judgedIssueTypes = new LinkedHashMap<>();
        List<CurationCaseResult> judgedCuration = report.curationResults().stream().map(result -> {
            DocumentSpec draft = data.documentOf(result.input().selectedDraftId());
            String relatedDocuments = result.expected().relatedDocumentIds().stream()
                    .map(data::documentOf)
                    .filter(Objects::nonNull)
                    .map(document -> document.title() + "\n" + document.markdown())
                    .reduce("", (first, second) -> first + "\n\n" + second);
            CurationActual actual = result.actual();
            CurationJudgement judgement = judge.judgeCuration(new CurationJudgeInput(
                    result.caseId(),
                    draft == null ? "（草稿不在数据集中）" : draft.markdown(),
                    relatedDocuments.isBlank() ? "（无相关正式文档）" : relatedDocuments,
                    result.expected().issueType(), result.expected().action(),
                    result.expected().finalResponse(), actual.finalResponse(),
                    workspaceMarkdown(actual.workspace()), result.expected().forbiddenDraftFacts()));
            judgedIssueTypes.put(result.caseId(), normalizeIssueType(judgement.issueType()));
            CurationVerdict verdict = result.verdict();
            CurationVerdict judged = new CurationVerdict(verdict.caseId(), verdict.issueType(), verdict.action(),
                    judgement.reason(), verdict.workspaceMatch(),
                    Boolean.TRUE.equals(judgement.actionCorrect()),
                    Boolean.TRUE.equals(judgement.unsafeWrite()),
                    Boolean.TRUE.equals(judgement.issueCorrect()));
            return new CurationCaseResult(result.caseId(), result.input(), result.expected(),
                    result.actual(), judged);
        }).toList();

        QaSummary qaSummary = AtlasEvalMetrics.qaSummary(
                judgedQa.stream().map(QaCaseResult::verdict).toList());
        CurationSummary curationSummary = AtlasEvalMetrics.curationSummary(
                judgedCuration.stream().map(CurationCaseResult::verdict).toList(), judgedIssueTypes);
        return new Report(report.datasetVersion(), report.projectIdentifier(), report.executedAt(),
                report.environment(), judgedQa, judgedCuration,
                AgentEvalReport.qaMetrics(qaSummary), AgentEvalReport.curationMetrics(curationSummary),
                report.gates());
    }

    /** 把 Judge 输出的问题类型归一化：NONE/空值与数据集标注一致，记为 null。 */
    private static String normalizeIssueType(String issueType) {
        if (issueType == null || issueType.isBlank() || "NONE".equals(issueType)) {
            return null;
        }
        return issueType;
    }

    /** 汇总工作草稿正文，供 Judge 判断动作与误写。 */
    private static String workspaceMarkdown(List<WorkspaceActual> workspace) {
        return workspace.stream()
                .map(document -> document.operation() + " baseline=" + document.baselineDocumentId()
                        + "\n" + document.markdown())
                .reduce("", (first, second) -> first + "\n\n" + second);
    }

    /** 汇总本轮实际提供给模型的检索原文（保留项），超长截断并注明。 */
    private static String retrievalText(QaActual actual) {
        StringBuilder builder = new StringBuilder();
        for (RetrievalActual retrieval : actual.retrievals()) {
            builder.append("检索查询：").append(retrieval.query()).append('\n');
            for (RetrievedActual document : retrieval.documents()) {
                if (!document.retained() || document.content() == null) {
                    continue;
                }
                builder.append("- ").append(document.title())
                        .append(document.truncated() ? "（已裁剪）" : "").append("：")
                        .append(document.content()).append('\n');
            }
        }
        if (builder.isEmpty()) {
            return "（本轮没有检索到证据）";
        }
        if (builder.length() > MAX_RETRIEVAL_CHARACTERS) {
            return builder.substring(0, MAX_RETRIEVAL_CHARACTERS) + "\n（超出部分已截断）";
        }
        return builder.toString();
    }

    /** 逐条输出 Judge 判定证据到 stdout，与报告内容一致。 */
    public static void printJudgeEvidence(Report report) {
        for (QaCaseResult result : report.qaResults()) {
            QaVerdict verdict = result.verdict();
            System.out.printf("测试证据：场景=Agent评估QA裁判，用例=%s，忠实度=%s，相关性=%s，理由=%s%n",
                    result.caseId(), verdict.faithfulness(), verdict.relevance(),
                    preview(verdict.reason(), 120));
        }
        for (CurationCaseResult result : report.curationResults()) {
            CurationVerdict verdict = result.verdict();
            System.out.printf("测试证据：场景=Agent评估知识整理裁判，用例=%s，判定问题=%s，问题识别正确=%s，"
                            + "动作正确=%s，误写=%s，理由=%s%n",
                    result.caseId(), result.expected().issueType() == null ? "NONE" : result.expected().issueType(),
                    verdict.issueCorrect(), verdict.actionCorrect(), verdict.unsafeWrite(),
                    preview(verdict.reason(), 120));
        }
        AgentEvalReport.QaMetrics qa = report.qaMetrics();
        AgentEvalReport.CurationMetrics curation = report.curationMetrics();
        System.out.printf("测试证据：场景=Agent评估裁判汇总，平均忠实度=%s，平均相关性=%s，"
                        + "问题识别正确率=%s，动作正确率=%.2f%%，误写率=%.2f%%，问题类型F1=%s%n",
                qa.averageFaithfulness(), qa.averageRelevance(), curation.issueCorrectRate(),
                curation.actionCorrectRate() * 100.0D, curation.unsafeWriteRate() * 100.0D,
                curation.issueTypeF1());
    }

    private static String preview(String text, int maxCodePoints) {
        if (text == null) {
            return "-";
        }
        int count = text.codePointCount(0, text.length());
        return count <= maxCodePoints ? text : text.substring(0, text.offsetByCodePoints(0, maxCodePoints)) + "…";
    }
}
