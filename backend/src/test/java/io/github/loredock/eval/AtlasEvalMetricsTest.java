package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationExpected;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationInput;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaExpected;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaInput;
import io.github.loredock.eval.AtlasAgentEvalFixture.WorkspaceExpectation;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasCurationEvalRunner.WorkspaceActual;
import io.github.loredock.eval.AtlasEvalMetrics.CurationSummary;
import io.github.loredock.eval.AtlasEvalMetrics.CurationVerdict;
import io.github.loredock.eval.AtlasEvalMetrics.QaSummary;
import io.github.loredock.eval.AtlasEvalMetrics.QaVerdict;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.eval.AtlasQaEvalRunner.RetrievalActual;
import io.github.loredock.eval.AtlasQaEvalRunner.RetrievedActual;
import io.github.loredock.qa.api.QaQuestion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 评估指标计算的单元测试：Top-5 候选提取、QA 判定、知识整理工作区匹配/动作近似/
 * 误写检测与汇总指标，保护文档第 9 节指标口径。
 */
class AtlasEvalMetricsTest {

    private static final QaInput QA_INPUT = new QaInput("atlas", "main", "测试问题");
    private static final CurationInput CURATION_INPUT = new CurationInput("atlas", 720001L, "整理候选材料");

    /**
     * 业务目的：Top-5 必须跨全部检索按最高相关度去重排序且不超过 5 个，
     * 防止同一文档多次检索重复占位或候选顺序颠倒。
     */
    @Test
    void top5DeduplicatesByMaxRelevanceAndLimitsToFive() {
        List<RetrievalActual> retrievals = List.of(
                new RetrievalActual("第一次检索", List.of(
                        retrieved(710001L, 0.7), retrieved(710002L, 0.9))),
                new RetrievalActual("第二次检索", List.of(
                        retrieved(710001L, 0.95), retrieved(710003L, 0.8), retrieved(710004L, 0.6),
                        retrieved(710005L, 0.5), retrieved(710006L, 0.4), retrieved(710007L, 0.3))));

        List<Long> top5 = AtlasEvalMetrics.top5DocumentIds(retrievals);

        assertThat(top5).containsExactly(710001L, 710002L, 710003L, 710004L, 710005L);
        System.out.printf("测试证据：场景=Top5候选提取，候选=%s，大小=%d%n", top5, top5.size());
    }

    /**
     * 业务目的：可回答用例在 Top-5 命中任一预期来源且引用覆盖全部来源时，
     * 准确率、召回率与引用覆盖判定必须同时为真。
     */
    @Test
    void qaVerdictAnswersWithTop5HitFullRecallAndCitationCoverage() {
        QaCase qaCase = qaCase("QA-001", "ANSWER", List.of(710001L, 710007L), "INSUFFICIENT_EVIDENCE");
        QaActual actual = qaActual("QA-001", QaQuestion.Status.COMPLETED, "ANSWER", null,
                List.of(710001L, 710007L), List.of(710007L, 710001L, 710003L));

        QaVerdict verdict = AtlasEvalMetrics.qaVerdict(actual, qaCase);

        assertThat(verdict.completed()).isTrue();
        assertThat(verdict.answerable()).isTrue();
        assertThat(verdict.hitTop5()).isTrue();
        assertThat(verdict.top5Recall()).isEqualTo(1.0D);
        assertThat(verdict.citationCoverage()).isTrue();
        assertThat(verdict.resultTypeMatch()).isTrue();
        System.out.printf("测试证据：场景=QA判定命中，用例=%s，Top5命中=%s，召回=%.2f，引用覆盖=%s%n",
                verdict.caseId(), verdict.hitTop5(), verdict.top5Recall(), verdict.citationCoverage());
    }

    /**
     * 业务目的：Top-5 未命中任何预期来源时准确率与召回率判定必须为假/0，
     * 防止把检索失败当作命中计入指标。
     */
    @Test
    void qaVerdictMissesTop5WithZeroRecall() {
        QaCase qaCase = qaCase("QA-002", "ANSWER", List.of(710001L, 710002L), "INSUFFICIENT_EVIDENCE");
        QaActual actual = qaActual("QA-002", QaQuestion.Status.COMPLETED, "ANSWER", null,
                List.of(), List.of(710007L, 710008L, 710009L, 710010L, 710011L));

        QaVerdict verdict = AtlasEvalMetrics.qaVerdict(actual, qaCase);

        assertThat(verdict.hitTop5()).isFalse();
        assertThat(verdict.top5Recall()).isZero();
        assertThat(verdict.citationCoverage()).isFalse();
        System.out.printf("测试证据：场景=QA判定未命中，用例=%s，Top5命中=%s，召回=%.2f%n",
                verdict.caseId(), verdict.hitTop5(), verdict.top5Recall());
    }

    /**
     * 业务目的：拒答用例必须同时匹配结果类型与拒答原因，且不参与 Top-5 统计。
     */
    @Test
    void qaVerdictMatchesRefusalTypeAndReason() {
        QaCase qaCase = qaCase("QA-035", "REFUSAL", List.of(), "INSUFFICIENT_EVIDENCE");
        QaActual actual = qaActual("QA-035", QaQuestion.Status.COMPLETED, "REFUSAL", "INSUFFICIENT_EVIDENCE",
                List.of(), List.of());

        QaVerdict verdict = AtlasEvalMetrics.qaVerdict(actual, qaCase);

        assertThat(verdict.resultTypeMatch()).isTrue();
        assertThat(verdict.refusalReasonMatch()).isTrue();
        assertThat(verdict.answerable()).isFalse();
        assertThat(verdict.hitTop5()).isFalse();
        assertThat(verdict.top5Recall()).isZero();
        System.out.printf("测试证据：场景=QA判定拒答，用例=%s，结果匹配=%s，原因匹配=%s%n",
                verdict.caseId(), verdict.resultTypeMatch(), verdict.refusalReasonMatch());
    }

    /**
     * 业务目的：汇总指标必须按文档第 9.1 节口径计算 Top-5 准确率与平均召回率，
     * Judge 分数缺失时平均值保持为空而不是 0。
     */
    @Test
    void qaSummaryComputesAccuracyAndAverageRecall() {
        QaVerdict hit = AtlasEvalMetrics.qaVerdict(
                qaActual("QA-001", QaQuestion.Status.COMPLETED, "ANSWER", null,
                        List.of(710001L), List.of(710001L, 710002L)),
                qaCase("QA-001", "ANSWER", List.of(710001L), "INSUFFICIENT_EVIDENCE"));
        QaVerdict miss = AtlasEvalMetrics.qaVerdict(
                qaActual("QA-002", QaQuestion.Status.COMPLETED, "ANSWER", null,
                        List.of(), List.of(710003L)),
                qaCase("QA-002", "ANSWER", List.of(710001L, 710002L), "INSUFFICIENT_EVIDENCE"));
        QaVerdict refusal = AtlasEvalMetrics.qaVerdict(
                qaActual("QA-035", QaQuestion.Status.COMPLETED, "REFUSAL", "INSUFFICIENT_EVIDENCE",
                        List.of(), List.of()),
                qaCase("QA-035", "REFUSAL", List.of(), "INSUFFICIENT_EVIDENCE"));

        QaSummary summary = AtlasEvalMetrics.qaSummary(List.of(hit, miss, refusal));

        assertThat(summary.caseCount()).isEqualTo(3);
        assertThat(summary.answerableCount()).isEqualTo(2);
        assertThat(summary.top5Accuracy()).isEqualTo(0.5D);
        assertThat(summary.averageTop5Recall()).isEqualTo(0.5D);
        assertThat(summary.resultTypeMatchRate()).isEqualTo(1.0D);
        assertThat(summary.averageFaithfulness()).isNull();
        System.out.printf("测试证据：场景=QA汇总，准确率=%.2f，平均召回=%.2f，结果匹配率=%.2f%n",
                summary.top5Accuracy(), summary.averageTop5Recall(), summary.resultTypeMatchRate());
    }

    /**
     * 业务目的：预期 MODIFY 基线文档时，实际工作区必须包含相同基线的 MODIFY 文档才算处置正确。
     */
    @Test
    void curationVerdictMatchesExpectedModifyWorkspace() {
        CurationCase curationCase = curationCase("CUR-002", "DUPLICATE", "MERGE",
                new WorkspaceExpectation("MODIFY", 710004L), List.of());
        CurationActual actual = curationActual("CUR-002",
                List.of(new WorkspaceActual("MODIFY", 710004L, "# 合并后的正文")));

        CurationVerdict verdict = AtlasEvalMetrics.curationVerdict(actual, curationCase);

        assertThat(verdict.workspaceMatch()).isTrue();
        assertThat(verdict.actionCorrect()).isTrue();
        assertThat(verdict.unsafeWrite()).isFalse();
        System.out.printf("测试证据：场景=知识整理工作区匹配，用例=%s，匹配=%s，动作=%s%n",
                verdict.caseId(), verdict.workspaceMatch(), verdict.actionCorrect());
    }

    /**
     * 业务目的：预期不产生工作文档（NO_CHANGE/ASK_USER）时，实际写入了任何工作文档都必须判定不匹配。
     */
    @Test
    void curationVerdictRejectsUnexpectedWorkspaceWrite() {
        CurationCase curationCase = curationCase("CUR-001", "DUPLICATE", "NO_CHANGE", null, List.of());
        CurationActual actual = curationActual("CUR-001",
                List.of(new WorkspaceActual("ADD", null, "# 新文档")));

        CurationVerdict verdict = AtlasEvalMetrics.curationVerdict(actual, curationCase);

        assertThat(verdict.workspaceMatch()).isFalse();
        assertThat(verdict.actionCorrect()).isFalse();
        System.out.printf("测试证据：场景=知识整理意外写入，用例=%s，工作区匹配=%s，动作=%s%n",
                verdict.caseId(), verdict.workspaceMatch(), verdict.actionCorrect());
    }

    /**
     * 业务目的：ASK_USER 用例的确定性近似必须同时满足未写入工作区与最终回复请求人工确认，
     * 防止把只是复述冲突但没有请求确认的回复误判为处置正确。
     */
    @Test
    void curationVerdictAskUserRequiresConfirmationSignal() {
        CurationCase curationCase = curationCase("CUR-003", "CONFLICT", "ASK_USER", null,
                List.of("自动重试次数为 5 次"));
        CurationActual asking = curationActual("CUR-003", "请管理员确认正确次数", List.of());
        CurationActual silent = curationActual("CUR-003", "该结论存在冲突", List.of());

        CurationVerdict askingVerdict = AtlasEvalMetrics.curationVerdict(asking, curationCase);
        CurationVerdict silentVerdict = AtlasEvalMetrics.curationVerdict(silent, curationCase);

        assertThat(askingVerdict.actionCorrect()).isTrue();
        assertThat(silentVerdict.actionCorrect()).isFalse();
        System.out.printf("测试证据：场景=ASK_USER动作近似，请求确认=%s，未请求=%s%n",
                askingVerdict.actionCorrect(), silentVerdict.actionCorrect());
    }

    /**
     * 业务目的：冲突/缺失用例把禁止事实写入工作草稿时必须标记误写，
     * 用于计算不确定内容误写率。
     */
    @Test
    void curationVerdictDetectsForbiddenFactInWorkspace() {
        CurationCase curationCase = curationCase("CUR-004", "CONFLICT", "ASK_USER", null,
                List.of("MEMBER 可以跳过审核直接发布"));
        CurationActual actual = curationActual("CUR-004", List.of(new WorkspaceActual(
                "MODIFY", 710002L, "# 草稿\nMEMBER 可以跳过审核直接发布，无需等待管理员。")));

        CurationVerdict verdict = AtlasEvalMetrics.curationVerdict(actual, curationCase);

        assertThat(verdict.unsafeWrite()).isTrue();
        assertThat(verdict.actionCorrect()).isFalse();
        System.out.printf("测试证据：场景=误写检测，用例=%s，误写=%s%n", verdict.caseId(), verdict.unsafeWrite());
    }

    /**
     * 业务目的：汇总必须按第 9.2 节口径计算动作正确率与误写率，并提供 Judge 标签时的
     * 逐问题类型 F1，保证指标口径可复算。
     */
    @Test
    void curationSummaryComputesRatesAndIssueF1() {
        CurationVerdict conflictCorrect = verdict("CUR-003", "CONFLICT", true, false);
        CurationVerdict conflictWrong = verdict("CUR-004", "CONFLICT", false, true);
        CurationVerdict missingCorrect = verdict("CUR-005", "MISSING", true, false);
        CurationVerdict missingWrong = verdict("CUR-006", "MISSING", false, false);
        CurationVerdict normalAdd = verdict("CUR-007", null, true, false);

        CurationSummary summary = AtlasEvalMetrics.curationSummary(
                List.of(conflictCorrect, conflictWrong, missingCorrect, missingWrong, normalAdd),
                Map.of("CUR-003", "CONFLICT", "CUR-004", "CONFLICT", "CUR-005", "CONFLICT",
                        "CUR-006", "MISSING"));

        assertThat(summary.issueCaseCount()).isEqualTo(4);
        assertThat(summary.actionCorrectRate()).isEqualTo(0.5D);
        assertThat(summary.unsafeWriteRate()).isEqualTo(0.25D);
        assertThat(summary.issueTypeF1()).containsKey("CONFLICT");
        assertThat(summary.issueTypeF1().get("CONFLICT").precision()).isEqualTo(2.0D / 3.0D);
        assertThat(summary.issueTypeF1().get("CONFLICT").recall()).isEqualTo(1.0D);
        assertThat(summary.issueTypeF1().get("CONFLICT").f1()).isCloseTo(0.8D, org.assertj.core.data.Offset.offset(0.001D));
        assertThat(summary.issueTypeF1()).doesNotContainKey("DUPLICATE");
        System.out.printf("测试证据：场景=知识整理汇总，动作正确率=%.2f，误写率=%.2f，CONFLICT F1=%.2f%n",
                summary.actionCorrectRate(), summary.unsafeWriteRate(),
                summary.issueTypeF1().get("CONFLICT").f1());
    }

    private static CurationVerdict verdict(String caseId, String issueType, boolean actionCorrect, boolean unsafeWrite) {
        return new CurationVerdict(caseId, issueType, "ASK_USER", !unsafeWrite, actionCorrect, unsafeWrite, null, null);
    }

    private static QaCase qaCase(String caseId, String resultType, List<Long> documentIds, String refusalReason) {
        return new QaCase(caseId, "SINGLE_DOCUMENT", QA_INPUT,
                new QaExpected(resultType, refusalReason, "参考回答", documentIds));
    }

    private static QaActual qaActual(
            String caseId, QaQuestion.Status status, String resultType, String refusalReason,
            List<Long> citations, List<Long> top5
    ) {
        return new QaActual(caseId, "SINGLE_DOCUMENT", status, null, resultType, refusalReason,
                "实际回答", citations, List.of(), top5, 1000L);
    }

    private static CurationCase curationCase(
            String caseId, String issueType, String action,
            WorkspaceExpectation workspace, List<String> forbidden
    ) {
        return new CurationCase(caseId, CURATION_INPUT, new CurationExpected(
                issueType, List.of(), action, "参考最终回复", workspace, forbidden));
    }

    private static CurationActual curationActual(String caseId, List<WorkspaceActual> workspace) {
        return new CurationActual(caseId, KnowledgeTaskService.RunStatus.COMPLETED, null, "最终回复", workspace, 1000L);
    }

    private static CurationActual curationActual(String caseId, String finalResponse, List<WorkspaceActual> workspace) {
        return new CurationActual(caseId, KnowledgeTaskService.RunStatus.COMPLETED, null, finalResponse, workspace, 1000L);
    }

    private static RetrievedActual retrieved(Long documentId, double relevance) {
        return new RetrievedActual(documentId, "标题", relevance, true, false);
    }
}
