package io.github.loredock.eval;

import io.github.loredock.eval.AtlasAgentEvalFixture.CurationCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaCase;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasCurationEvalRunner.WorkspaceActual;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.eval.AtlasQaEvalRunner.RetrievalActual;
import io.github.loredock.qa.api.QaQuestion;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Atlas Agent 评估指标计算。
 *
 * <p>本类只计算不依赖 LLM Judge 的客观指标：QA 的 Top-5 准确率/召回率、结果类型与拒答原因匹配、
 * 引用覆盖；知识整理的预期工作区匹配、动作正确率（确定性近似）与不确定内容误写率。
 * 忠实度、相关性、问题识别等需要 LLM Judge 的字段保留为 {@code null}，由后续 Judge 接入填充。</p>
 */
public final class AtlasEvalMetrics {

    /** 判定最终回复是否请求人工确认的确定性近似关键词；真实判定由 LLM Judge 完成。 */
    private static final List<String> ASK_HUMAN_SIGNALS = List.of("请管理员", "管理员确认", "人工确认");
    /** Top-5 窗口大小：精确率的分母固定为 5，反映返回列表中期望文档的占比。 */
    private static final int TOP5_WINDOW = 5;

    private AtlasEvalMetrics() {
    }

    /**
     * 从全部检索记录计算 Top-5 候选：同一文档多次命中取最高相关度，按相关度降序、
     * 文档 ID 升序去重后取前 5。
     *
     * @param retrievals 按调用顺序的检索记录
     * @return 至多 5 个候选文档 ID，与检索次数无关
     */
    public static List<Long> top5DocumentIds(List<RetrievalActual> retrievals) {
        Map<Long, Double> bestRelevance = new LinkedHashMap<>();
        for (RetrievalActual retrieval : retrievals) {
            for (AtlasQaEvalRunner.RetrievedActual document : retrieval.documents()) {
                bestRelevance.merge(document.documentId(), document.relevance(), Math::max);
            }
        }
        return bestRelevance.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 计算单条 QA 用例的客观判定。
     *
     * <p>参与 Top-5 与引用统计的用例：ANSWER 用例，以及携带期望文档的 SOURCE_CONFLICT 拒答用例
     * （冲突拒答需要同时检索到冲突文档，检索质量可测）；无期望文档的证据不足拒答不参与，
     * 避免把"无目标可找"计入检索指标。</p>
     *
     * @param actual 实际结果
     * @param qaCase 评估用例
     * @return 逐项判定；LLM Judge 分数字段为空
     */
    public static QaVerdict qaVerdict(QaActual actual, QaCase qaCase) {
        boolean completed = actual.status() == QaQuestion.Status.COMPLETED;
        boolean answerable = "ANSWER".equals(qaCase.expected().resultType());
        boolean retrievalMeasurable = answerable || ("REFUSAL".equals(qaCase.expected().resultType())
                && !qaCase.expected().documentIds().isEmpty());
        boolean resultTypeMatch = completed && Objects.equals(
                qaCase.expected().resultType(), actual.resultType());
        boolean refusalReasonMatch = completed && "REFUSAL".equals(qaCase.expected().resultType())
                && Objects.equals(qaCase.expected().refusalReason(), actual.refusalReason());
        Set<Long> expected = Set.copyOf(qaCase.expected().documentIds());
        Set<Long> top5 = Set.copyOf(actual.top5DocumentIds());
        boolean hitTop5 = retrievalMeasurable && completed && top5.stream().anyMatch(expected::contains);
        long recalled = top5.stream().filter(expected::contains).count();
        double top5Recall = retrievalMeasurable && completed && !expected.isEmpty()
                ? (double) recalled / expected.size() : 0.0D;
        // 精确率以固定窗口 5 为分母：单目标用例命中时召回为 1.0，但 Top-5 中期望文档占比
        // 可能只有 1/5，精确率与召回率互补反映"目标被找回"与"返回列表噪声"两个侧面。
        double top5Precision = retrievalMeasurable && completed
                ? (double) recalled / TOP5_WINDOW : 0.0D;
        boolean citationCoverage = retrievalMeasurable && completed && !expected.isEmpty()
                && actual.citationDocumentIds().containsAll(expected);
        return new QaVerdict(
                actual.caseId(), qaCase.caseType(), null, null, null,
                completed, resultTypeMatch, refusalReasonMatch,
                answerable, retrievalMeasurable, hitTop5, top5Recall, top5Precision, citationCoverage);
    }

    /** @param verdicts 逐条 QA 判定 @return 汇总指标；Judge 分数无结果时平均值为空 */
    public static QaSummary qaSummary(List<QaVerdict> verdicts) {
        long measurable = verdicts.stream().filter(QaVerdict::retrievalMeasurable).count();
        long top5Hits = verdicts.stream().filter(QaVerdict::retrievalMeasurable).filter(QaVerdict::hitTop5).count();
        double top5Recall = verdicts.stream().filter(QaVerdict::retrievalMeasurable)
                .mapToDouble(QaVerdict::top5Recall).average().orElse(0.0D);
        double top5Precision = verdicts.stream().filter(QaVerdict::retrievalMeasurable)
                .mapToDouble(QaVerdict::top5Precision).average().orElse(0.0D);
        long resultTypeMatches = verdicts.stream().filter(QaVerdict::resultTypeMatch).count();
        long refusalMatches = verdicts.stream().filter(QaVerdict::refusalReasonMatch).count();
        Double averageFaithfulness = average(verdicts.stream()
                .map(QaVerdict::faithfulness).filter(Objects::nonNull).toList());
        Double averageRelevance = average(verdicts.stream()
                .map(QaVerdict::relevance).filter(Objects::nonNull).toList());
        return new QaSummary(
                verdicts.size(), measurable, top5Hits, measurable == 0 ? 0.0D : (double) top5Hits / measurable,
                top5Recall, top5Precision, resultTypeMatches,
                verdicts.isEmpty() ? 0.0D : (double) resultTypeMatches / verdicts.size(),
                refusalMatches, averageFaithfulness, averageRelevance);
    }

    /**
     * 计算单条知识整理用例的客观判定。
     *
     * <p>动作正确率采用确定性近似：NO_CHANGE/MERGE/ADD_OR_UPDATE 以实际工作区是否与预期一致判定，
     * ASK_USER 额外要求最终回复明确请求人工确认；问题识别正确与否属于 LLM Judge 判定，暂为空。</p>
     *
     * @param actual 实际结果
     * @param curationCase 评估用例
     * @return 逐项判定；issueCorrect 与 Judge 说明字段为空
     */
    public static CurationVerdict curationVerdict(CurationActual actual, CurationCase curationCase) {
        boolean workspaceMatch = workspaceMatches(curationCase, actual);
        boolean unsafeWrite = containsForbiddenFacts(curationCase, actual);
        boolean actionCorrect = actionCorrect(curationCase, actual, workspaceMatch);
        return new CurationVerdict(
                actual.caseId(), curationCase.expected().issueType(), curationCase.expected().action(),
                null, workspaceMatch, actionCorrect, unsafeWrite, null, null);
    }

    /**
     * 汇总知识整理指标。
     *
     * @param verdicts 逐条判定
     * @param judgedIssueTypes 可选：LLM Judge 给出的逐条问题识别标签（caseId -> 判定类型）；
     *                         为空时问题识别 F1 与识别正确率不计算
     * @return 汇总指标
     */
    public static CurationSummary curationSummary(
            List<CurationVerdict> verdicts,
            Map<String, String> judgedIssueTypes
    ) {
        List<CurationVerdict> issueCases = verdicts.stream()
                .filter(verdict -> verdict.issueType() != null).toList();
        long actionCorrect = issueCases.stream().filter(CurationVerdict::actionCorrect).count();
        List<CurationVerdict> conflictOrMissing = verdicts.stream()
                .filter(verdict -> "CONFLICT".equals(verdict.issueType())
                        || "MISSING".equals(verdict.issueType()))
                .toList();
        long unsafeWrites = conflictOrMissing.stream().filter(CurationVerdict::unsafeWrite).count();
        Map<String, IssueTypeF1> issueF1 = new LinkedHashMap<>();
        if (judgedIssueTypes != null && !judgedIssueTypes.isEmpty()) {
            for (String type : List.of("DUPLICATE", "CONFLICT", "MISSING")) {
                // 只统计数据集中实际存在的预期类型，避免把无样本类型输出为误导性的 0 分。
                boolean present = issueCases.stream().anyMatch(verdict -> type.equals(verdict.issueType()));
                if (present) {
                    issueF1.put(type, issueTypeF1(type, verdicts, judgedIssueTypes));
                }
            }
        }
        long correctByJudge = issueCases.stream()
                .filter(verdict -> verdict.issueCorrect() != null && verdict.issueCorrect()).count();
        return new CurationSummary(
                verdicts.size(), issueCases.size(),
                issueCases.isEmpty() ? 0.0D : (double) actionCorrect / issueCases.size(),
                conflictOrMissing.isEmpty() ? 0.0D : (double) unsafeWrites / conflictOrMissing.size(),
                unsafeWrites, conflictOrMissing.size(),
                judgedIssueTypes == null || judgedIssueTypes.isEmpty() ? null : (double) correctByJudge / issueCases.size(),
                Map.copyOf(issueF1));
    }

    private static boolean workspaceMatches(CurationCase curationCase, CurationActual actual) {
        AtlasAgentEvalFixture.WorkspaceExpectation expected = curationCase.expected().workspace();
        if (expected == null) {
            return actual.workspace().isEmpty();
        }
        return actual.workspace().stream().anyMatch(document -> expected.operation().equals(document.operation())
                && Objects.equals(expected.baselineDocumentId(), document.baselineDocumentId()));
    }

    private static boolean actionCorrect(CurationCase curationCase, CurationActual actual, boolean workspaceMatch) {
        String action = curationCase.expected().action();
        if ("ASK_USER".equals(action)) {
            return workspaceMatch && asksHumanConfirmation(actual.finalResponse());
        }
        return workspaceMatch;
    }

    private static boolean asksHumanConfirmation(String finalResponse) {
        if (finalResponse == null || finalResponse.isBlank()) {
            return false;
        }
        return ASK_HUMAN_SIGNALS.stream().anyMatch(finalResponse::contains);
    }

    private static boolean containsForbiddenFacts(CurationCase curationCase, CurationActual actual) {
        List<String> forbidden = curationCase.expected().forbiddenDraftFacts();
        if (forbidden.isEmpty()) {
            return false;
        }
        String workspaceText = actual.workspace().stream()
                .map(WorkspaceActual::markdown)
                .filter(Objects::nonNull)
                .reduce("", (first, second) -> first + "\n" + second);
        return forbidden.stream()
                .filter(fact -> fact != null && !fact.isBlank())
                .anyMatch(workspaceText::contains);
    }

    private static IssueTypeF1 issueTypeF1(
            String type,
            List<CurationVerdict> verdicts,
            Map<String, String> judgedIssueTypes
    ) {
        long truePositive = 0;
        long falsePositive = 0;
        long falseNegative = 0;
        for (CurationVerdict verdict : verdicts) {
            String expected = verdict.issueType();
            String judged = judgedIssueTypes.get(verdict.caseId());
            if (type.equals(judged) && type.equals(expected)) {
                truePositive++;
            } else if (type.equals(judged)) {
                falsePositive++;
            } else if (type.equals(expected)) {
                falseNegative++;
            }
        }
        double precision = truePositive + falsePositive == 0 ? 0.0D
                : (double) truePositive / (truePositive + falsePositive);
        double recall = truePositive + falseNegative == 0 ? 0.0D
                : (double) truePositive / (truePositive + falseNegative);
        double f1 = precision + recall == 0.0D ? 0.0D : 2.0D * precision * recall / (precision + recall);
        return new IssueTypeF1(precision, recall, f1);
    }

    private static Double average(List<Integer> values) {
        return values.isEmpty() ? null : values.stream().mapToInt(Integer::intValue).average().orElseThrow();
    }

    /**
     * 单条 QA 用例判定；reason/faithfulness/relevance 由 LLM Judge 填充，未接入时为空。
     * answerable 表示预期为 ANSWER；retrievalMeasurable 表示参与检索指标统计
     * （ANSWER 或携带期望文档的冲突拒答）。
     */
    public record QaVerdict(
            String caseId,
            String caseType,
            String reason,
            Integer faithfulness,
            Integer relevance,
            boolean completed,
            boolean resultTypeMatch,
            boolean refusalReasonMatch,
            boolean answerable,
            boolean retrievalMeasurable,
            boolean hitTop5,
            double top5Recall,
            double top5Precision,
            boolean citationCoverage
    ) {
    }

    /**
     * QA 汇总指标：top5HitRate 为至少命中一个期望文档的用例占比（附加信息）；
     * 准确率 = 期望文档在最终 Top-5 中的出现率（top5Precision，分母固定 5）；
     * 召回率 = 期望文档被 Top-5 找回的比例（top5Recall）；Judge 平均值无结果时为空。
     */
    public record QaSummary(
            int caseCount,
            long answerableCount,
            long top5HitCount,
            double top5HitRate,
            double top5Recall,
            double top5Precision,
            long resultTypeMatchCount,
            double resultTypeMatchRate,
            long refusalReasonMatchCount,
            Double averageFaithfulness,
            Double averageRelevance
    ) {
    }

    /** 单条知识整理用例判定；reason/issueCorrect/judgedIssueType 由 LLM Judge 填充，未接入时为空。 */
    public record CurationVerdict(
            String caseId,
            String issueType,
            String action,
            String reason,
            boolean workspaceMatch,
            boolean actionCorrect,
            boolean unsafeWrite,
            Boolean issueCorrect,
            String judgedIssueType
    ) {
    }

    /** 知识整理汇总指标；问题识别相关指标需要 LLM Judge，未接入时为空。 */
    public record CurationSummary(
            int caseCount,
            int issueCaseCount,
            double actionCorrectRate,
            double unsafeWriteRate,
            long unsafeWriteCount,
            long conflictOrMissingCount,
            Double issueCorrectRate,
            Map<String, IssueTypeF1> issueTypeF1
    ) {
        public CurationSummary {
            issueTypeF1 = issueTypeF1 == null ? Map.of() : Map.copyOf(issueTypeF1);
        }
    }

    /** 单类问题的 Precision/Recall/F1。 */
    public record IssueTypeF1(Double precision, Double recall, Double f1) {
    }
}
