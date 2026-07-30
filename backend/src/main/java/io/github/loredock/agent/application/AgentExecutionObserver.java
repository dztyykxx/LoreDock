package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEventType;

/** 执行适配器向应用层提交可公开阶段、工具摘要和文本增量的回调。 */
@FunctionalInterface
public interface AgentExecutionObserver {

    /** @param type 公开事件类型 @param safePayload 已裁剪且允许持久化的载荷 */
    void onEvent(AgentEventType type, String safePayload);
}
