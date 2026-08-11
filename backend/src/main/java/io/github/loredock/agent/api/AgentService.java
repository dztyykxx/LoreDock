package io.github.loredock.agent.api;

import java.time.Duration;
import java.util.List;

/** 向 QA 提供运行受理、授权终态读取和已提交公开事件的统一契约。 */
public interface AgentService {

    /**
     * @param request 已认证操作者提交的项目问答请求
     * @return 已受理或按幂等键复用的运行
     * @throws AgentRequestException 输入有效但 Agent 当前不能受理，或幂等键冲突
     */
    AgentRun start(StartRequest request);

    /**
     * @param request 已认证操作者提交的全局（全库）问答请求；不解析项目主数据
     * @return 已受理或按幂等键复用的运行
     * @throws AgentRequestException 输入有效但 Agent 当前不能受理，或幂等键冲突
     */
    AgentRun startGlobal(GlobalStartRequest request);

    /**
     * @param runId 运行标识
     * @param operatorId 操作者稳定标识
     * @return 已复核操作者与项目访问范围的运行终态
     * @throws AgentRunNotFoundException 运行不存在或当前操作者不可见
     */
    AgentRun get(Long runId, String operatorId);

    /** @return 指定序号之后的有界已提交公开事件 */
    List<AgentEvent> listEvents(Long runId, String operatorId, long afterSequence, int limit);

    /** @return 当前最后一个已提交公开事件序号 */
    long lastEventSequence(Long runId, String operatorId);

    /** @return 当前进程内后续提交事件的可关闭订阅；历史事件仍须通过 listEvents 补读 */
    Subscription subscribe(Long runId);

    /**
     * @param idempotencyKey 当前操作者内唯一幂等键
     * @param operatorId 操作者稳定标识
     * @param operatorRole ADMIN 或 MEMBER
     * @param projectIdentifier 已启用项目标识
     * @param branch 可选分支
     * @param question 1～2000 个 Unicode 字符的问题
     * @param conversationHistory 同会话已完成且受服务端裁剪的非证据消息
     */
    record StartRequest(
            String idempotencyKey,
            String operatorId,
            String operatorRole,
            String projectIdentifier,
            String branch,
            String question,
            List<ConversationMessage> conversationHistory
    ) {
        public StartRequest {
            conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        }

        /** 保留不携带历史的独立运行调用兼容。 */
        public StartRequest(
                String idempotencyKey,
                String operatorId,
                String operatorRole,
                String projectIdentifier,
                String branch,
                String question
        ) {
            this(idempotencyKey, operatorId, operatorRole, projectIdentifier, branch, question, List.of());
        }
    }

    /**
     * @param idempotencyKey 当前操作者内唯一幂等键
     * @param operatorId 操作者稳定标识
     * @param operatorRole ADMIN 或 MEMBER
     * @param question 1～2000 个 Unicode 字符的问题
     * @param conversationHistory 同会话已完成且受服务端裁剪的非证据消息
     */
    record GlobalStartRequest(
            String idempotencyKey,
            String operatorId,
            String operatorRole,
            String question,
            List<ConversationMessage> conversationHistory
    ) {
        public GlobalStartRequest {
            conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        }
    }

    /**
     * 只帮助模型理解当前问题指代的历史消息；服务端不会为其生成当前运行 evidenceId。
     *
     * @param role USER 或 ASSISTANT
     * @param content 已完成公开正文
     * @param occurredAt 原轮次消息时间
     */
    record ConversationMessage(String role, String content, java.time.Instant occurredAt) {
    }

    /** 当前进程提交后事件订阅。 */
    interface Subscription extends AutoCloseable {
        /** @return 超时内下一条已提交事件；没有时为 null */
        AgentEvent poll(Duration timeout) throws InterruptedException;

        @Override
        void close();
    }
}
