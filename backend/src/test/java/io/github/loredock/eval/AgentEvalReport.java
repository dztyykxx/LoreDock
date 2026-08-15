package io.github.loredock.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationExpected;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationInput;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaExpected;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaInput;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasEvalMetrics.CurationSummary;
import io.github.loredock.eval.AtlasEvalMetrics.CurationVerdict;
import io.github.loredock.eval.AtlasEvalMetrics.QaSummary;
import io.github.loredock.eval.AtlasEvalMetrics.QaVerdict;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Agent 评估机器报告：保存数据集版本、逐条实际结果与客观判定、汇总指标与完成门禁。
 *
 * <p>报告字段与《Agent 评估测试数据构造要求》第 5、7 节建议的实际结果格式保持一致，
 * LLM Judge 输出字段（faithfulness/relevance/issueCorrect/reason）由后续 Judge 接入填充。</p>
 */
public final class AgentEvalReport {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private AgentEvalReport() {
    }

    /** 汇总一次评估运行并组装机器报告。 */
    public static Report build(
            EvalData data,
            List<QaActual> qaActuals,
            List<CurationActual> curationActuals,
            List<QaVerdict> qaVerdicts,
            List<CurationVerdict> curationVerdicts,
            Map<String, String> judgedIssueTypes,
            String environment,
            String executedAt
    ) {
        QaSummary qaSummary = AtlasEvalMetrics.qaSummary(qaVerdicts);
        CurationSummary curationSummary = AtlasEvalMetrics.curationSummary(curationVerdicts, judgedIssueTypes);
        List<QaCaseResult> qaResults = qaActuals.stream()
                .map(actual -> {
                    QaCase qaCase = data.qaCases().stream()
                            .filter(candidate -> candidate.caseId().equals(actual.caseId())).findFirst().orElseThrow();
                    QaVerdict verdict = qaVerdicts.stream()
                            .filter(candidate -> candidate.caseId().equals(actual.caseId())).findFirst().orElseThrow();
                    return new QaCaseResult(qaCase.caseId(), qaCase.caseType(), qaCase.input(), qaCase.expected(),
                            actual, verdict);
                })
                .toList();
        List<CurationCaseResult> curationResults = curationActuals.stream()
                .map(actual -> {
                    CurationCase curationCase = data.curationCases().stream()
                            .filter(candidate -> candidate.caseId().equals(actual.caseId())).findFirst().orElseThrow();
                    CurationVerdict verdict = curationVerdicts.stream()
                            .filter(candidate -> candidate.caseId().equals(actual.caseId())).findFirst().orElseThrow();
                    return new CurationCaseResult(curationCase.caseId(), curationCase.input(), curationCase.expected(),
                            actual, verdict);
                })
                .toList();
        boolean qaAllCompleted = qaActuals.stream()
                .allMatch(actual -> actual.status() == io.github.loredock.qa.api.QaQuestion.Status.COMPLETED);
        boolean curationAllCompleted = curationActuals.stream()
                .allMatch(actual -> actual.status() != null && actual.status().terminal());
        Gates gates = new Gates(qaAllCompleted, curationAllCompleted, false);
        return new Report(
                data.manifest().datasetVersion(), data.manifest().projectIdentifier(), executedAt, environment,
                qaResults, curationResults,
                new QaMetrics(qaSummary.caseCount(), qaSummary.answerableCount(), qaSummary.top5Accuracy(),
                        qaSummary.averageTop5Recall(), qaSummary.resultTypeMatchRate(),
                        qaSummary.averageFaithfulness(), qaSummary.averageRelevance()),
                new CurationMetrics(curationSummary.caseCount(), curationSummary.issueCaseCount(),
                        curationSummary.actionCorrectRate(), curationSummary.unsafeWriteRate(),
                        curationSummary.issueTypeF1()),
                gates);
    }

    /** @return 报告输出路径；可通过系统属性 {@code loredock.agent-eval.output} 覆盖 */
    public static Path defaultOutputPath() {
        return Path.of(System.getProperty("loredock.agent-eval.output", "target/atlas-agent-eval-report.json"))
                .toAbsolutePath().normalize();
    }

    /**
     * 把机器报告写入 JSON 文件。
     *
     * @param report 组装好的报告
     * @param output 输出路径
     * @return 已标记 reportWritten=true 的写后报告，供调用方直接断言门禁
     */
    public static Report write(Report report, Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Report written = new Report(report.datasetVersion(), report.projectIdentifier(), report.executedAt(),
                report.environment(), report.qaResults(), report.curationResults(),
                report.qaMetrics(), report.curationMetrics(),
                new Gates(report.gates().qaAllCompleted(), report.gates().curationAllCompleted(), true));
        JSON.writeValue(output.toFile(), written);
        return written;
    }

    /** 逐条输出可核验的评估证据到 stdout，保持与断言一致。 */
    public static void printEvidence(Report report) {
        for (QaCaseResult result : report.qaResults()) {
            QaVerdict verdict = result.verdict();
            QaActual actual = result.actual();
            System.out.printf("测试证据：场景=Agent评估QA，用例=%s，类型=%s，范围=%s/%s，问题=%s，终态=%s，"
                            + "结果=%s，拒答原因=%s，引用数=%d，Top5候选=%s，Top5命中=%s，引用覆盖=%s，"
                            + "检索次数=%d，耗时毫秒=%d%n",
                    result.caseId(), result.caseType(), result.input().projectIdentifier(), result.input().branch(),
                    preview(result.input().question(), 60), actual.status(), actual.resultType(),
                    actual.refusalReason() == null ? "-" : actual.refusalReason(),
                    actual.citationDocumentIds().size(), actual.top5DocumentIds(),
                    verdict.hitTop5(), verdict.citationCoverage(), actual.retrievals().size(), actual.elapsedMillis());
        }
        for (CurationCaseResult result : report.curationResults()) {
            CurationVerdict verdict = result.verdict();
            CurationActual actual = result.actual();
            System.out.printf("测试证据：场景=Agent评估知识整理，用例=%s，草稿=%s，预期问题=%s，预期动作=%s，"
                            + "终态=%s，工作区数=%d，工作区匹配=%s，动作正确=%s，误写=%s，最终回复=%s%n",
                    result.caseId(), result.input().selectedDraftId(),
                    result.expected().issueType() == null ? "NONE" : result.expected().issueType(),
                    result.expected().action(), actual.status(), actual.workspace().size(),
                    verdict.workspaceMatch(), verdict.actionCorrect(), verdict.unsafeWrite(),
                    preview(actual.finalResponse(), 120));
        }
        QaMetrics qa = report.qaMetrics();
        CurationMetrics curation = report.curationMetrics();
        System.out.printf("测试证据：场景=Agent评估汇总，数据集=%s，项目=%s，QA用例=%d，可回答=%d，"
                        + "Top5准确率=%.2f%%，Top5平均召回=%.2f%%，结果类型匹配率=%.2f%%，"
                        + "知识整理用例=%d，动作正确率=%.2f%%，误写率=%.2f%%，"
                        + "门禁：QA全部完成=%s，知识整理全部完成=%s%n",
                report.datasetVersion(), report.projectIdentifier(), qa.caseCount(), qa.answerableCount(),
                qa.top5Accuracy() * 100.0D, qa.averageTop5Recall() * 100.0D, qa.resultTypeMatchRate() * 100.0D,
                curation.caseCount(), curation.actionCorrectRate() * 100.0D, curation.unsafeWriteRate() * 100.0D,
                report.gates().qaAllCompleted(), report.gates().curationAllCompleted());
    }

    private static String preview(String text, int maxCodePoints) {
        if (text == null) {
            return "-";
        }
        int count = text.codePointCount(0, text.length());
        return count <= maxCodePoints ? text : text.substring(0, text.offsetByCodePoints(0, maxCodePoints)) + "…";
    }

    /** 完整评估报告。 */
    public record Report(
            String datasetVersion,
            String projectIdentifier,
            String executedAt,
            String environment,
            List<QaCaseResult> qaResults,
            List<CurationCaseResult> curationResults,
            QaMetrics qaMetrics,
            CurationMetrics curationMetrics,
            Gates gates
    ) {
        public Report {
            qaResults = qaResults == null ? List.of() : List.copyOf(qaResults);
            curationResults = curationResults == null ? List.of() : List.copyOf(curationResults);
        }
    }

    /** 单条 QA 用例：输入、预期、实际与客观判定。 */
    public record QaCaseResult(
            String caseId, String caseType, QaInput input, QaExpected expected,
            QaActual actual, QaVerdict verdict
    ) {
    }

    /** 单条知识整理用例：输入、预期、实际与客观判定。 */
    public record CurationCaseResult(
            String caseId, CurationInput input, CurationExpected expected,
            CurationActual actual, CurationVerdict verdict
    ) {
    }

    /** QA 汇总指标（与文档第 9.1 节对应；Judge 平均值无结果时为空）。 */
    public record QaMetrics(
            int caseCount, long answerableCount, double top5Accuracy, double averageTop5Recall,
            double resultTypeMatchRate, Double averageFaithfulness, Double averageRelevance
    ) {
    }

    /** 知识整理汇总指标（与文档第 9.2 节对应；问题识别 F1 需要 Judge）。 */
    public record CurationMetrics(
            int caseCount, int issueCaseCount, double actionCorrectRate, double unsafeWriteRate,
            Map<String, AtlasEvalMetrics.IssueTypeF1> issueTypeF1
    ) {
        public CurationMetrics {
            issueTypeF1 = issueTypeF1 == null ? Map.of() : Map.copyOf(issueTypeF1);
        }
    }

    /** 完成门禁：全部用例达到预期终态且报告已写出。 */
    public record Gates(boolean qaAllCompleted, boolean curationAllCompleted, boolean reportWritten) {
        /** @return 全部门禁是否通过 */
        public boolean allPassed() {
            return qaAllCompleted && curationAllCompleted && reportWritten;
        }
    }
}
