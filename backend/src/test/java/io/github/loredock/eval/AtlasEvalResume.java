package io.github.loredock.eval;

import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.eval.AgentEvalReport.CurationCaseResult;
import io.github.loredock.eval.AgentEvalReport.QaCaseResult;
import io.github.loredock.eval.AgentEvalReport.Report;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.qa.api.QaQuestion;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评估断点续跑：长跑中断或部分失败后，读取上一轮报告，只重跑未达到完成终态的用例，
 * 已完成的用例复用上一轮实际结果，合并后重新生成报告。
 *
 * <p>每次运行使用全新的 Testcontainers 数据库并重新灌入评估环境，因此续跑不依赖旧库数据，
 * 也不需要清理旧运行记录；续跑只决定"跑哪些用例"和"合并哪些旧结果"。</p>
 */
public final class AtlasEvalResume {

    private AtlasEvalResume() {
    }

    /**
     * @param data 评估数据集（决定全部候选用例）
     * @param previous 上一轮报告；为空表示无续跑基线
     * @return 需要重跑的 QA 用例 caseId（上一轮缺失或未达到 COMPLETED），保持数据集顺序
     */
    public static Set<String> pendingQaCaseIds(AtlasAgentEvalFixture.EvalData data, Report previous) {
        Set<String> completed = previous == null ? Set.of() : previous.qaResults().stream()
                .filter(result -> result.actual().status() == QaQuestion.Status.COMPLETED)
                .map(QaCaseResult::caseId)
                .collect(Collectors.toSet());
        return data.qaCases().stream()
                .map(AtlasAgentEvalFixture.QaCase::caseId)
                .filter(caseId -> !completed.contains(caseId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * @param data 评估数据集（决定全部候选用例）
     * @param previous 上一轮报告；为空表示无续跑基线
     * @return 需要重跑的知识整理用例 caseId（上一轮缺失或未达到 COMPLETED），保持数据集顺序
     */
    public static Set<String> pendingCurationCaseIds(AtlasAgentEvalFixture.EvalData data, Report previous) {
        Set<String> completed = previous == null ? Set.of() : previous.curationResults().stream()
                .filter(result -> result.actual().status() == KnowledgeTaskService.RunStatus.COMPLETED)
                .map(CurationCaseResult::caseId)
                .collect(Collectors.toSet());
        return data.curationCases().stream()
                .map(AtlasAgentEvalFixture.CurationCase::caseId)
                .filter(caseId -> !completed.contains(caseId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** @param previous 上一轮报告 @param caseId 用例标识 @return 上一轮该 QA 用例的实际结果；不存在时为空 */
    public static QaActual previousQaActual(Report previous, String caseId) {
        if (previous == null) {
            return null;
        }
        return previous.qaResults().stream()
                .filter(result -> result.caseId().equals(caseId))
                .map(QaCaseResult::actual)
                .findFirst().orElse(null);
    }

    /** @param previous 上一轮报告 @param caseId 用例标识 @return 上一轮该知识整理用例的实际结果；不存在时为空 */
    public static CurationActual previousCurationActual(Report previous, String caseId) {
        if (previous == null) {
            return null;
        }
        return previous.curationResults().stream()
                .filter(result -> result.caseId().equals(caseId))
                .map(CurationCaseResult::actual)
                .findFirst().orElse(null);
    }
}
