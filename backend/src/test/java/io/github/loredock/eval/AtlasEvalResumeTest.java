package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationExpected;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationInput;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaExpected;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaInput;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasEvalMetrics.CurationVerdict;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.eval.AgentEvalReport.Report;
import io.github.loredock.qa.api.QaQuestion;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 评估断点续跑逻辑的单元测试：只重跑未达到完成终态的用例，已完成的复用上一轮结果。
 */
class AtlasEvalResumeTest {

    private static final QaInput QA_INPUT = new QaInput("atlas", "main", "测试问题");
    private static final CurationInput CURATION_INPUT = new CurationInput("atlas", 720001L, "整理候选材料");

    /**
     * 业务目的：上一轮已 COMPLETED 的用例必须从待重跑集合中剔除，
     * 未执行或 FAILED 的用例必须保留，防止断点续跑重复消耗模型调用。
     */
    @Test
    void pendingQaExcludesCompletedAndKeepsFailedAndMissing() {
        EvalData data = AtlasAgentEvalFixture.load();
        QaActual completed = qaActual("QA-001", QaQuestion.Status.COMPLETED);
        QaActual failed = qaActual("QA-002", QaQuestion.Status.FAILED);
        Report previous = AgentEvalReport.build(data,
                List.of(completed, failed), List.of(),
                List.of(AtlasEvalMetrics.qaVerdict(completed, qaCase("QA-001")),
                        AtlasEvalMetrics.qaVerdict(failed, qaCase("QA-002"))),
                List.of(), null, "unit-test", Instant.now().toString());

        Set<String> pending = AtlasEvalResume.pendingQaCaseIds(data, previous);

        assertThat(pending).doesNotContain("QA-001").contains("QA-002", "QA-003", "QA-035");
        assertThat(pending).hasSize(39);
        System.out.printf("测试证据：场景=续跑QA集合，完成=%d，待重跑=%d%n",
                data.qaCases().size() - pending.size(), pending.size());
    }

    /**
     * 业务目的：上一轮报告的已复用结果必须能按 caseId 取回，
     * 不存在的用例返回空而不是报错。
     */
    @Test
    void previousActualLookupReturnsReusableResultOrNull() {
        EvalData data = AtlasAgentEvalFixture.load();
        QaActual completed = qaActual("QA-001", QaQuestion.Status.COMPLETED);
        CurationActual curationCompleted = curationActual("CUR-001");
        Report previous = AgentEvalReport.build(data, List.of(completed), List.of(curationCompleted),
                List.of(AtlasEvalMetrics.qaVerdict(completed, qaCase("QA-001"))),
                List.of(AtlasEvalMetrics.curationVerdict(curationCompleted, curationCase("CUR-001"))),
                null, "unit-test", Instant.now().toString());

        assertThat(AtlasEvalResume.previousQaActual(previous, "QA-001")).isEqualTo(completed);
        assertThat(AtlasEvalResume.previousQaActual(previous, "QA-999")).isNull();
        assertThat(AtlasEvalResume.previousCurationActual(previous, "CUR-001")).isEqualTo(curationCompleted);
        assertThat(AtlasEvalResume.previousCurationActual(previous, "CUR-999")).isNull();
        System.out.println("测试证据：场景=续跑结果复用，已存在结果可取回，缺失结果返回空");
    }

    /**
     * 业务目的：上一轮知识整理已 COMPLETED 的用例必须剔除，
     * 其余用例（含上一轮缺失的）全部进入待重跑集合。
     */
    @Test
    void pendingCurationExcludesCompletedCases() {
        EvalData data = AtlasAgentEvalFixture.load();
        CurationActual completed = curationActual("CUR-001");
        Report previous = AgentEvalReport.build(data, List.of(), List.of(completed),
                List.of(),
                List.of(AtlasEvalMetrics.curationVerdict(completed, curationCase("CUR-001"))),
                null, "unit-test", Instant.now().toString());

        Set<String> pending = AtlasEvalResume.pendingCurationCaseIds(data, previous);

        assertThat(pending).doesNotContain("CUR-001").contains("CUR-002", "CUR-008");
        assertThat(pending).hasSize(7);
        System.out.printf("测试证据：场景=续跑知识整理集合，完成=%d，待重跑=%d%n",
                data.curationCases().size() - pending.size(), pending.size());
    }

    /**
     * 业务目的：没有上一轮报告时（首次运行或报告被删除），全部用例都应进入待重跑集合。
     */
    @Test
    void pendingIsFullDatasetWhenNoPreviousReport() {
        EvalData data = AtlasAgentEvalFixture.load();

        assertThat(AtlasEvalResume.pendingQaCaseIds(data, null)).hasSize(40);
        assertThat(AtlasEvalResume.pendingCurationCaseIds(data, null)).hasSize(8);
        System.out.println("测试证据：场景=续跑无基线，全部用例进入待重跑集合");
    }

    private static QaCase qaCase(String caseId) {
        return new QaCase(caseId, "SINGLE_DOCUMENT", QA_INPUT,
                new QaExpected("ANSWER", null, "参考回答", List.of(710001L)));
    }

    private static CurationCase curationCase(String caseId) {
        return new CurationCase(caseId, CURATION_INPUT,
                new CurationExpected("DUPLICATE", List.of(710007L), "NO_CHANGE", "参考最终回复", null, List.of()));
    }

    private static QaActual qaActual(String caseId, QaQuestion.Status status) {
        return new QaActual(caseId, "SINGLE_DOCUMENT", status, null, "ANSWER", null,
                "实际回答", List.of(710001L), List.of(), List.of(710001L), 1000L);
    }

    private static CurationActual curationActual(String caseId) {
        return new CurationActual(caseId, KnowledgeTaskService.RunStatus.COMPLETED, null,
                "最终回复", List.of(), 1000L);
    }
}
