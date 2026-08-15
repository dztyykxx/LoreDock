package io.github.loredock.eval;

import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识整理评估运行器：逐条执行评估数据集中的知识整理用例，从真实任务快照和工作区草稿中收集实际结果。
 *
 * <p>每条用例由单个 {@code selectedDraftId} 转为单元素数组启动，{@code idempotencyKey} 使用
 * {@code caseId}，触发原因固定为“Agent 评估”；最终回复取最后一条非空、无 Tool Call 的
 * {@code COORDINATOR_AGENT} 消息，工作区正文来自实际 {@code DraftRevision.markdown}。</p>
 */
public final class AtlasCurationEvalRunner {

    /** 评估统一操作者；知识整理由管理员视角发起。 */
    public static final String OPERATOR_ID = "admin";
    private static final String TRIGGER_REASON = "Agent 评估";
    private static final String TARGET_SKILL = "knowledge-curator";
    /** 评估目标系统配置属性；默认使用统一通用提示词，不随用例特化。 */
    public static final String GOAL_PROPERTY = "loredock.agent-eval.curation-goal";
    /** 通用知识整理目标：对所有用例一致，不泄漏预期处置，与 knowledge-curator Skill 职责对齐。 */
    public static final String DEFAULT_GOAL = "整理候选材料：核对候选草稿与已发布正式知识的关系，识别重复、矛盾、过期与关键缺失；"
            + "内容完整且与正式知识一致时按规范整理为工作文档，重复内容不重复创建，"
            + "无法确定时向管理员说明并等待确认，不写入未经确认的结论。";

    private final KnowledgeTaskService tasks;
    private final KnowledgeDraftService drafts;
    private final String goal;

    /**
     * @param tasks 知识任务统一契约入口
     * @param drafts 工作草稿读取端口
     */
    public AtlasCurationEvalRunner(KnowledgeTaskService tasks, KnowledgeDraftService drafts) {
        this(tasks, drafts, System.getProperty(GOAL_PROPERTY, DEFAULT_GOAL));
    }

    /**
     * @param tasks 知识任务统一契约入口
     * @param drafts 工作草稿读取端口
     * @param goal 系统统一配置的知识整理目标提示词
     */
    public AtlasCurationEvalRunner(KnowledgeTaskService tasks, KnowledgeDraftService drafts, String goal) {
        this.tasks = tasks;
        this.drafts = drafts;
        this.goal = goal;
    }

    /**
     * 按数据集顺序串行执行全部知识整理用例。
     *
     * @param data 已校验的评估数据集
     * @param perCaseTimeout 单条用例等待终态的超时
     * @return 与数据集顺序一致的逐条实际结果
     */
    public List<CurationActual> runAll(AtlasAgentEvalFixture.EvalData data, Duration perCaseTimeout) {
        return runAll(data, perCaseTimeout, data.curationCases().size());
    }

    /**
     * 串行执行数据集前 N 条知识整理用例；冒烟验证时用少量真实案例确认链路可运行，
     * 避免在流程未验证前消耗全量模型调用。
     *
     * @param data 已校验的评估数据集
     * @param perCaseTimeout 单条用例等待终态的超时
     * @param caseLimit 最多执行的用例数，按数据集顺序取前 N 条
     * @return 与数据集顺序一致的逐条实际结果
     */
    public List<CurationActual> runAll(AtlasAgentEvalFixture.EvalData data, Duration perCaseTimeout, int caseLimit) {
        List<CurationActual> actuals = new ArrayList<>();
        for (AtlasAgentEvalFixture.CurationCase curationCase
                : data.curationCases().stream().limit(caseLimit).toList()) {
            actuals.add(runCase(curationCase, perCaseTimeout));
        }
        return List.copyOf(actuals);
    }

    /**
     * 执行单条知识整理用例：启动任务、等待运行终态、提取最终回复与实际工作区。
     *
     * @param curationCase 单条知识整理用例
     * @param perCaseTimeout 等待终态的超时
     * @return 该用例的实际结果
     */
    public CurationActual runCase(AtlasAgentEvalFixture.CurationCase curationCase, Duration perCaseTimeout) {
        long startedNanos = System.nanoTime();
        KnowledgeTaskService.KnowledgeTask started = tasks.start(new KnowledgeTaskService.StartRequest(
                curationCase.caseId(), OPERATOR_ID, curationCase.input().projectIdentifier(),
                List.of(curationCase.input().selectedDraftId()),
                KnowledgeTaskService.TriggerType.MANUAL, TRIGGER_REASON, TARGET_SKILL,
                goal));
        KnowledgeTaskService.KnowledgeTask terminal = awaitTerminal(started.conversationId(), perCaseTimeout);
        KnowledgeTaskService.KnowledgeTaskRun run = terminal.runs().getLast();
        String finalResponse = terminal.messages().stream()
                .filter(message -> message.role() == KnowledgeTaskService.MessageRole.COORDINATOR_AGENT)
                .filter(message -> message.content() != null && !message.content().isBlank())
                .reduce((first, second) -> second)
                .map(KnowledgeTaskService.KnowledgeTaskMessage::content)
                .orElse(null);
        List<WorkspaceActual> workspace = workspaceActuals(terminal, run.runId());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        return new CurationActual(
                curationCase.caseId(), run.status(), run.errorCode(), finalResponse, workspace, elapsedMillis);
    }

    private KnowledgeTaskService.KnowledgeTask awaitTerminal(Long conversationId, Duration timeout) {
        KnowledgeTaskService.KnowledgeTask snapshot = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            snapshot = tasks.get(conversationId, OPERATOR_ID);
            KnowledgeTaskService.RunStatus status = snapshot.runs().getLast().status();
            if (status.terminal()) {
                return snapshot;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("知识整理评估等待终态被中断：" + conversationId, exception);
            }
        }
        throw new IllegalStateException("知识整理评估用例未在超时内达到终态：" + snapshot.runs().getLast().status());
    }

    private List<WorkspaceActual> workspaceActuals(KnowledgeTaskService.KnowledgeTask task, Long runId) {
        KnowledgeDraftService.AccessContext context = new KnowledgeDraftService.AccessContext(
                OPERATOR_ID, task.projectIdentifier(), task.conversationId(), runId);
        List<WorkspaceActual> actuals = new ArrayList<>();
        for (KnowledgeDraftService.WorkspaceDocument document : task.workspaceDocuments()) {
            // 空 v0 基线只是供 Agent 恢复的占位，不构成本轮处置动作。
            if (document.currentRevision() <= 0) {
                continue;
            }
            KnowledgeDraftService.DraftRevision revision = drafts.read(
                    new KnowledgeDraftService.ReadRequest(context, document.draftId(), null));
            actuals.add(new WorkspaceActual(
                    document.operation().name(), document.baselineDocumentId(), revision.markdown()));
        }
        return List.copyOf(actuals);
    }

    /**
     * 单条知识整理用例的实际结果。
     *
     * @param status 最近运行终态
     * @param errorCode 运行失败码；非失败时为空
     * @param finalResponse 最后一条非空、无 Tool Call 的 COORDINATOR_AGENT 消息
     * @param workspace 实际工作区文档（操作、基线文档与正文）
     * @param elapsedMillis 用例总耗时
     */
    public record CurationActual(
            String caseId,
            KnowledgeTaskService.RunStatus status,
            String errorCode,
            String finalResponse,
            List<WorkspaceActual> workspace,
            long elapsedMillis
    ) {
        public CurationActual {
            workspace = workspace == null ? List.of() : List.copyOf(workspace);
        }
    }

    /** 实际工作区文档：operation 为 ADD/MODIFY，markdown 为实际草稿修订正文。 */
    public record WorkspaceActual(String operation, Long baselineDocumentId, String markdown) {
    }
}
