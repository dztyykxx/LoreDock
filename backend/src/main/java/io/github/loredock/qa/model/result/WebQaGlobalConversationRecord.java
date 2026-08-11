package io.github.loredock.qa.model.result;

import java.time.Instant;

/**
 * 跨项目最近会话列表行：含项目主数据显示名，供侧栏标注"全局"或"项目：名称"。
 *
 * @param projectName 项目显示名；GLOBAL 会话为空
 */
public record WebQaGlobalConversationRecord(
        Long id,
        String operatorId,
        Long projectId,
        String projectIdentifier,
        String projectName,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Instant lastQuestionAt
) {
}
