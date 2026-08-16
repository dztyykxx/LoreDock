package io.github.loredock.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 离线评判运行器：读取已生成的评估报告，逐条调用 LLM Judge，回填判定并重算指标。
 *
 * <p>评判只读报告与数据集原文，不重跑 Agent；QA 判定填入忠实度/相关性/理由，
 * 知识整理判定以 Judge 的问题识别、动作与误写结论替换确定性近似，并计算问题识别
 * 正确率与各问题类型 F1。</p>
 *
 * <p>支持逐用例落盘与断点续跑：每评判完一条用例立即把当前进度写回评判报告路径，
 * 中断后重跑读取已有评判报告，跳过已完成用例（QA 以忠实度非空、知识整理以问题识别
 * 判定非空为完成标志），不浪费已完成的模型调用。</p>
 */
public final class AtlasEvalJudgeRunner {

    /** 检索原文送入 Judge 的最大字符数，超出部分截断并注明，避免超大上下文。 */
    private static final int MAX_RETRIEVAL_CHARACTERS = 16000;
    private static final ObjectMapper CHECKPOINT_MAPPER = new ObjectMapper();

    private AtlasEvalJudgeRunner() {
    }

    /**
     * 全量评判（不落盘），供单元测试与不需要断点续跑的场景使用。
     *
     * @param report 未评判的评估报告
     * @param data 评估数据集（提供草稿与正式文档全文）
     * @param judge LLM Judge
     * @return 已回填 Judge 判定并重算指标的评估报告
     */
    public static Report judge(Report report, EvalData data, AtlasEvalJudge judge) {
        return judgeWithCheckpoint(report, null, data, judge);
    }

    /**
     * 离线评判（逐用例落盘 + 断点续跑）。
     *
     * <p>若评判报告路径上已存在上一轮评判结果，跳过其中已完成的用例，只评判剩余用例；
     * 每完成一条用例立即把当前进度写回该路径，中断后重跑不会丢失已完成结果。</p>
     *
     * @param report 未评判的评估报告
     * @param judgedOutput 评判报告输出路径；为空时不写盘（等价于全量评判）
     * @param data 评估数据集
     * @param judge LLM Judge
     * @return 已回填 Judge 判定并重算指标的评估报告
     */
    public static Report judgeWithCheckpoint(Report report, Path judgedOutput, EvalData data, AtlasEvalJudge judge) {
        Report checkpoint = readCheckpoint(judgedOutput);
        Set<String> doneQa = doneQa(checkpoint);
        Set<String> doneCuration = doneCuration(checkpoint);

        List<QaCaseResult> qaResults = new ArrayList<>(report.qaResults());
        for (int index = 0; index < qaResults.size(); index++) {
            QaCaseResult result = qaResults.get(index);
            QaCaseResult resumed = resumedQa(checkpoint, result.caseId());
            if (doneQa.contains(result.caseId()) && resumed != null) {
                // 断点续跑：上一轮已评判，复用判定，不再调用模型。
                qaResults.set(index, resumed);
                continue;
            }
            QaJudgement judgement = judge.judgeQa(new QaJudgeInput(
                    result.caseId(), result.input().question(), result.expected().resultText(),
                    result.actual().resultText(), retrievalText(result.actual()),
                    result.actual().citationDocumentIds()));
            // 客观字段（命中/召回/准确率/引用覆盖等）按当前指标口径从实际结果重算，
            // 不沿用旧报告可能过期的判定（例如冲突拒答参与统计的新口径），评判报告无需重跑 Agent。
            QaVerdict objective = data.qaCases().stream()
                    .filter(qaCase -> qaCase.caseId().equals(result.caseId()))
                    .findFirst()
                    .map(qaCase -> AtlasEvalMetrics.qaVerdict(result.actual(), qaCase))
                    .orElse(result.verdict());
            QaVerdict judged = new QaVerdict(objective.caseId(), objective.caseType(),
                    judgement.reason(), judgement.faithfulness(), judgement.relevance(),
                    objective.completed(), objective.resultTypeMatch(), objective.refusalReasonMatch(),
                    objective.answerable(), objective.retrievalMeasurable(),
                    objective.hitTop5(), objective.top5Recall(), objective.top5Precision(),
                    objective.citationCoverage());
            qaResults.set(index, new QaCaseResult(result.caseId(), result.caseType(), result.input(),
                    result.expected(), result.actual(), judged));
            writeCheckpoint(judgedOutput, report, qaResults, new ArrayList<>(report.curationResults()));
        }

        Map<String, String> judgedIssueTypes = new LinkedHashMap<>();
        List<CurationCaseResult> curationResults = new ArrayList<>(report.curationResults());
        for (int index = 0; index < curationResults.size(); index++) {
            CurationCaseResult result = curationResults.get(index);
            CurationCaseResult resumed = resumedCuration(checkpoint, result.caseId());
            if (doneCuration.contains(result.caseId()) && resumed != null) {
                curationResults.set(index, resumed);
                if (resumed.verdict().judgedIssueType() != null) {
                    judgedIssueTypes.put(resumed.caseId(), resumed.verdict().judgedIssueType());
                }
                continue;
            }
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
                    Boolean.TRUE.equals(judgement.issueCorrect()),
                    normalizeIssueType(judgement.issueType()));
            curationResults.set(index, new CurationCaseResult(result.caseId(), result.input(), result.expected(),
                    result.actual(), judged));
            writeCheckpoint(judgedOutput, report, qaResults, curationResults);
        }

        QaSummary qaSummary = AtlasEvalMetrics.qaSummary(
                qaResults.stream().map(QaCaseResult::verdict).toList());
        CurationSummary curationSummary = AtlasEvalMetrics.curationSummary(
                curationResults.stream().map(CurationCaseResult::verdict).toList(), judgedIssueTypes);
        Report finalReport = new Report(report.datasetVersion(), report.projectIdentifier(), report.executedAt(),
                report.environment(), List.copyOf(qaResults), List.copyOf(curationResults),
                AgentEvalReport.qaMetrics(qaSummary), AgentEvalReport.curationMetrics(curationSummary),
                report.gates());
        writeCheckpoint(judgedOutput, finalReport, finalReport.qaResults(), finalReport.curationResults());
        return finalReport;
    }

    /** 读取已有评判报告作为续跑基线；不存在时返回空。 */
    private static Report readCheckpoint(Path judgedOutput) {
        if (judgedOutput == null || !Files.isRegularFile(judgedOutput)) {
            return null;
        }
        try {
            return CHECKPOINT_MAPPER.readValue(judgedOutput.toFile(), Report.class);
        } catch (IOException exception) {
            throw new IllegalStateException("评判续跑读取失败：" + judgedOutput, exception);
        }
    }

    /** @return 已评判的 QA 用例 ID（忠实度已填充）；无基线时为空集合 */
    private static Set<String> doneQa(Report checkpoint) {
        if (checkpoint == null) {
            return Set.of();
        }
        return checkpoint.qaResults().stream()
                .filter(result -> result.verdict().faithfulness() != null)
                .map(QaCaseResult::caseId)
                .collect(Collectors.toSet());
    }

    /** @return 已评判的知识整理用例 ID（问题识别判定已填充）；无基线时为空集合 */
    private static Set<String> doneCuration(Report checkpoint) {
        if (checkpoint == null) {
            return Set.of();
        }
        return checkpoint.curationResults().stream()
                .filter(result -> result.verdict().issueCorrect() != null)
                .map(CurationCaseResult::caseId)
                .collect(Collectors.toSet());
    }

    private static QaCaseResult resumedQa(Report checkpoint, String caseId) {
        if (checkpoint == null) {
            return null;
        }
        return checkpoint.qaResults().stream()
                .filter(candidate -> candidate.caseId().equals(caseId))
                .findFirst()
                .orElse(null);
    }

    private static CurationCaseResult resumedCuration(Report checkpoint, String caseId) {
        if (checkpoint == null) {
            return null;
        }
        return checkpoint.curationResults().stream()
                .filter(candidate -> candidate.caseId().equals(caseId))
                .findFirst()
                .orElse(null);
    }

    /** 把当前评判进度写回评判报告路径；输出路径为空时不写。 */
    private static void writeCheckpoint(
            Path output, Report base, List<QaCaseResult> qaResults, List<CurationCaseResult> curationResults
    ) {
        if (output == null) {
            return;
        }
        QaSummary qaSummary = AtlasEvalMetrics.qaSummary(
                qaResults.stream().map(QaCaseResult::verdict).toList());
        CurationSummary curationSummary = AtlasEvalMetrics.curationSummary(
                curationResults.stream().map(CurationCaseResult::verdict).toList(),
                judgedIssueTypes(curationResults));
        Report snapshot = new Report(base.datasetVersion(), base.projectIdentifier(), base.executedAt(),
                base.environment(), List.copyOf(qaResults), List.copyOf(curationResults),
                AgentEvalReport.qaMetrics(qaSummary), AgentEvalReport.curationMetrics(curationSummary),
                base.gates());
        try {
            CHECKPOINT_MAPPER.writeValue(output.toFile(), snapshot);
        } catch (IOException exception) {
            throw new IllegalStateException("评判进度写盘失败：" + output, exception);
        }
    }

    /** 从已评判的判定中提取 Judge 问题类型标签（未评判的用例不参与）。 */
    private static Map<String, String> judgedIssueTypes(List<CurationCaseResult> results) {
        Map<String, String> map = new LinkedHashMap<>();
        for (CurationCaseResult result : results) {
            if (result.verdict().judgedIssueType() != null) {
                map.put(result.caseId(), result.verdict().judgedIssueType());
            }
        }
        return map;
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
