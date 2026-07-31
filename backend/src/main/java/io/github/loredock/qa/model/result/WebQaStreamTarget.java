package io.github.loredock.qa.model.result;

import io.github.loredock.agent.api.AgentRun;

/** SSE 每轮重新授权后得到的问答身份与最新运行数据库快照。 */
public record WebQaStreamTarget(WebQaQuestionRecord question, AgentRun run) {
}
