package io.github.loredock.eval;

import io.github.loredock.agent.model.result.AgentRunRetrieval;
import io.github.loredock.agent.service.AgentRetrievalService;
import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.api.QaService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * QA 评估运行器：逐条执行评估数据集中的 QA 用例，从真实运行快照和检索记录中收集实际结果。
 *
 * <p>每条用例使用 {@code caseId} 作为操作者范围幂等键、固定新会话（conversationId=null），
 * 等待真实 Agent 运行达到终态后读取最终回答、引用与 {@code agent_run_retrieval} 中
 * 模型本轮实际看到的检索原文，供 Top-5 与忠实度指标使用。</p>
 */
public final class AtlasQaEvalRunner {

    /** 评估统一操作者；数据集不保存操作者，由评估程序生成。 */
    public static final String OPERATOR_ID = "member";
    public static final String OPERATOR_ROLE = "MEMBER";

    private final QaService questions;
    private final AgentRetrievalService retrievals;

    /**
     * @param questions QA 统一契约入口
     * @param retrievals 知识检索评估记录读取端口
     */
    public AtlasQaEvalRunner(QaService questions, AgentRetrievalService retrievals) {
        this.questions = questions;
        this.retrievals = retrievals;
    }

    /**
     * 按数据集顺序串行执行全部 QA 用例，保证检索记录与运行互不干扰。
     *
     * @param data 已校验的评估数据集
     * @param perCaseTimeout 单条用例等待终态的超时
     * @return 与数据集顺序一致的逐条实际结果
     */
    public List<QaActual> runAll(AtlasAgentEvalFixture.EvalData data, Duration perCaseTimeout) {
        List<QaActual> actuals = new ArrayList<>();
        for (AtlasAgentEvalFixture.QaCase qaCase : data.qaCases()) {
            actuals.add(runCase(qaCase, perCaseTimeout));
        }
        return List.copyOf(actuals);
    }

    /**
     * 执行单条 QA 用例：创建问答、等待终态、读取检索记录并计算 Top-5 候选。
     *
     * @param qaCase 单条 QA 用例
     * @param perCaseTimeout 等待终态的超时
     * @return 该用例的实际结果
     */
    public QaActual runCase(AtlasAgentEvalFixture.QaCase qaCase, Duration perCaseTimeout) {
        long startedNanos = System.nanoTime();
        QaQuestion created = questions.create(new QaService.CreateRequest(
                OPERATOR_ID, OPERATOR_ROLE, qaCase.caseId(),
                qaCase.input().projectIdentifier(), qaCase.input().branch(),
                qaCase.input().question()));
        QaQuestion terminal = awaitTerminal(created.questionId(), qaCase.input().projectIdentifier(), perCaseTimeout);
        List<AgentRunRetrieval> recorded = retrievals.findByRunId(terminal.runId());
        List<RetrievalActual> retrievalActuals = recorded.stream()
                .map(record -> new RetrievalActual(record.query(),
                        record.documents().stream()
                                .map(document -> new RetrievedActual(
                                        document.documentId(), document.title(), document.relevance(),
                                        document.retained(), document.truncated()))
                                .toList()))
                .toList();
        List<Long> top5 = AtlasEvalMetrics.top5DocumentIds(retrievalActuals);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        return new QaActual(
                qaCase.caseId(), qaCase.caseType(), terminal.status(), terminal.errorCode() == null ? null
                : terminal.errorCode().name(),
                terminal.resultType() == null ? null : terminal.resultType().name(),
                terminal.refusalReason() == null ? null : terminal.refusalReason().name(),
                terminal.resultText(),
                terminal.citations().stream().map(QaQuestion.Citation::documentId).toList(),
                retrievalActuals, top5, elapsedMillis);
    }

    private QaQuestion awaitTerminal(Long questionId, String projectIdentifier, Duration timeout) {
        QaQuestion snapshot = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            snapshot = questions.detail(new QaService.DetailQuery(OPERATOR_ID, projectIdentifier, questionId));
            if (snapshot.status() == QaQuestion.Status.COMPLETED
                    || snapshot.status() == QaQuestion.Status.FAILED
                    || snapshot.status() == QaQuestion.Status.TERMINATED) {
                return snapshot;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("问答评估等待终态被中断：" + questionId, exception);
            }
        }
        throw new IllegalStateException("问答评估用例未在超时内达到终态：" + snapshot);
    }

    /** 单次检索实际记录：查询词与模型本轮实际看到的候选文档。 */
    public record RetrievalActual(String query, List<RetrievedActual> documents) {
        public RetrievalActual {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    /** 单个候选文档实际记录；retained=false 表示未进入模型上下文。 */
    public record RetrievedActual(
            Long documentId, String title, double relevance, boolean retained, boolean truncated
    ) {
    }

    /**
     * 单条 QA 用例的实际结果：运行终态、最终回答、引用、检索原文与 Top-5 候选。
     *
     * @param status 运行终态
     * @param errorCode 运行失败码；非失败时为空
     * @param resultType 回答或拒答
     * @param refusalReason 拒答原因
     * @param resultText 模型最终回答
     * @param citationDocumentIds 实际引用文档
     * @param retrievals 按调用顺序保存的检索记录
     * @param top5DocumentIds 跨检索按相关度排序去重后的 Top-5 候选
     * @param elapsedMillis 用例总耗时
     */
    public record QaActual(
            String caseId,
            String caseType,
            QaQuestion.Status status,
            String errorCode,
            String resultType,
            String refusalReason,
            String resultText,
            List<Long> citationDocumentIds,
            List<RetrievalActual> retrievals,
            List<Long> top5DocumentIds,
            long elapsedMillis
    ) {
        public QaActual {
            citationDocumentIds = citationDocumentIds == null ? List.of() : List.copyOf(citationDocumentIds);
            retrievals = retrievals == null ? List.of() : List.copyOf(retrievals);
            top5DocumentIds = top5DocumentIds == null ? List.of() : List.copyOf(top5DocumentIds);
        }
    }
}
