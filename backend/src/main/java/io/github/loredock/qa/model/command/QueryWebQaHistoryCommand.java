package io.github.loredock.qa.model.command;

/** @param operatorId 当前操作者 @param projectIdentifier URL 项目标识 @param cursor 可空游标 @param limit 页大小 */
public record QueryWebQaHistoryCommand(
        String operatorId,
        String projectIdentifier,
        String cursor,
        int limit
) {
}
